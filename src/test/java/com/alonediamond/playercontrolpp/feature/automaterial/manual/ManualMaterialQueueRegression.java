package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.google.gson.stream.MalformedJsonException;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;

/** 验证串行批次的新增量、失败恢复及持久化边界。 */
public final class ManualMaterialQueueRegression {
    private static int checks;

    public static int run() {
        checks = 0;
        try {
            Path file = Files.createTempFile("playercontrolpp-manual-queue", ".json");
            Files.delete(file);
            try { exercise(file); }
            finally { Files.deleteIfExists(file); }
        } catch (IOException e) {
            throw new AssertionError("manual queue regression IO", e);
        }
        check(ManualMaterialSearch.matches("xmmb", "橡木木板", "minecraft:oak_planks"), "拼音首字母");
        check(ManualMaterialSearch.matches("xiangmu", "橡木木板", "minecraft:oak_planks"), "拼音全拼");
        check(ManualMaterialSearch.matches("oak_pla", "橡木木板", "minecraft:oak_planks"), "注册名包含搜索");
        check(ManualMaterialSearch.matches("木板", "橡木木板", "minecraft:oak_planks"), "中文名包含搜索");
        check(!ManualMaterialSearch.matches("dirt", "橡木木板", "minecraft:oak_planks"), "不相关词不命中");
        return checks;
    }

    private static void exercise(Path file) {
        ManualMaterialQueue queue = new ManualMaterialQueue(file);
        check(!queue.start(item -> 0), "空队列不启动备货");
        ManualMaterialBatch first = queue.addBatch("First");
        first.setAmount("minecraft:stone", 32);
        ManualMaterialBatch second = queue.addBatch("Second");
        second.setAmount("minecraft:stone", 16);
        check(queue.save(), "队列定义写盘");

        ManualMaterialQueue restored = new ManualMaterialQueue(file);
        check(restored.batches().size() == 2 && restored.batches().get(1).amount("minecraft:stone") == 16,
                "重新加载保留批次顺序与新增量");
        final int[] held = { 10 };
        check(restored.start(item -> held[0]), "首批启动");
        check(restored.activeBatch().targets().getFirst().neededCount == 42,
                "首批按当前自有库存加新增量得到需求总量");
        restored.failActiveBatch("no_container");
        check(restored.state() == ManualMaterialQueue.State.PAUSED && restored.activeIndex() == 0,
                "失败暂停保留当前批次");
        held[0] = 20;
        check(restored.resume(item -> held[0]) && restored.activeBatch().targets().getFirst().neededCount == 42,
                "恢复沿用原基线，不重复增加目标");
        check(!restored.completeActiveBatch(item -> held[0]) && restored.activeIndex() == 0,
                "未满足首批时禁止跳到下一批");
        held[0] = 42;
        check(restored.completeActiveBatch(item -> held[0]) && restored.activeIndex() == 1,
                "首批满足后推进第二批");
        check(restored.activeBatch().targets().getFirst().neededCount == 58,
                "第二批以当前库存重新建立总目标");
        held[0] = 58;
        check(restored.completeActiveBatch(item -> held[0])
                && restored.state() == ManualMaterialQueue.State.COMPLETE,
                "最后一批满足后完成队列");
        restored.stop();
        check(restored.state() == ManualMaterialQueue.State.IDLE
                && restored.batches().getFirst().amounts().equals(Map.of("minecraft:stone", 32)),
                "停止仅清运行状态，定义不变");
        restored.batches().get(1).removeItem("minecraft:stone");
        restored.batches().get(1).setAmount("minecraft:dirt", 1);
        Map<Item, Integer> owned = new IdentityHashMap<>();
        check(restored.start(item -> owned.getOrDefault(item, 0)), "新一轮从第一批重新启动");
        owned.put(Items.STONE, 32);
        check(restored.completeActiveBatch(item -> owned.getOrDefault(item, 0))
                && restored.activeBatch().targets().stream().anyMatch(e -> e.item == Items.STONE && e.neededCount == 32),
                "第二批携带首批石头的保留目标");
        owned.put(Items.STONE, 0);
        owned.put(Items.DIRT, 1);
        check(!restored.completeActiveBatch(item -> owned.getOrDefault(item, 0)),
                "第二批合成吃掉首批材料后不能宣称完成");
        owned.put(Items.STONE, 32);
        check(restored.completeActiveBatch(item -> owned.getOrDefault(item, 0))
                && restored.state() == ManualMaterialQueue.State.COMPLETE,
                "补齐被消耗的前批材料后才完成队列");
        restored.stop();
        try {
            Files.writeString(file, "{broken json");
            ManualMaterialQueue corrupt = new ManualMaterialQueue(file);
            // 只捕获这个故意损坏文件的预期警告；真实运行和其他回归的异常仍正常输出。
            try (ExpectedQueueWarning warning = new ExpectedQueueWarning()) {
                check(corrupt.batches().isEmpty() && Files.exists(file.resolveSibling(file.getFileName() + ".corrupt-1")),
                        "损坏队列被隔离且不加载伪条目");
                check(warning.isMalformedJsonWarning(), "损坏JSON记录一条可诊断的预期警告");
            }
            check(corrupt.save(), "隔离成功后可以写入新的队列定义");
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".corrupt-1"));
        } catch (IOException e) {
            throw new AssertionError("manual queue corruption IO", e);
        }
    }

    private static final class ExpectedQueueWarning extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getLogger(Playercontrolpp.MOD_ID);
        private final boolean additive = logger.isAdditive();
        private final List<LogEvent> events = new ArrayList<>();

        ExpectedQueueWarning() {
            super("expected-queue-warning", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
            logger.setAdditive(false);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        boolean isMalformedJsonWarning() {
            if (events.size() != 1) return false;
            LogEvent event = events.getFirst();
            if (event.getLevel() != Level.WARN
                    || !event.getMessage().getFormattedMessage().startsWith("Failed to read manual materials;")) {
                return false;
            }
            for (Throwable cause = event.getThrown(); cause != null; cause = cause.getCause()) {
                if (cause instanceof MalformedJsonException) return true;
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            logger.setAdditive(additive);
            stop();
        }
    }

    private static void check(boolean ok, String scenario) {
        if (!ok) throw new AssertionError(scenario);
        checks++;
    }
}
