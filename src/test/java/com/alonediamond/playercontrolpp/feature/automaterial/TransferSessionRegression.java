package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.container.TransferSession;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

/** 连续存盒与无进展回归；复用生产代码的扫描、快照和换菜单逻辑。 */
final class TransferSessionRegression {
    private static int checks;

    static int run() {
        checks = 0;
        storeAcrossPartlyFullBoxes();
        rejectionAndRollback();
        return checks;
    }

    private static void storeAcrossPartlyFullBoxes() {
        SimpleContainer inventory = new SimpleContainer(36);
        for (int i = 0; i < 24; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        for (int i = 24; i < 34; i++) inventory.setItem(i, new ItemStack(Items.STONE, 64));
        inventory.setItem(34, new ItemStack(Items.SHULKER_BOX));
        inventory.setItem(35, new ItemStack(Items.SHULKER_BOX));
        List<Slot> slots = slots(inventory);
        SimpleContainer first = new SimpleContainer(27);
        for (int i = 0; i < 26; i++) first.setItem(i, new ItemStack(Items.DIRT, 64));
        first.setItem(26, new ItemStack(Items.STONE, 60));
        SimpleContainer second = new SimpleContainer(27);
        TransferSession session = new TransferSession();
        int[] scanned = {0};
        int[] clicks = {0};
        drain(session, first, slots, scanned, clicks);
        check(count(first, Items.STONE) == 64, "前盒只能再存四个");
        check(count(inventory, Items.STONE) == 636, "前盒装满后保留未存材料");
        drain(session, second, slots, scanned, clicks);
        check(count(second, Items.STONE) == 636, "第二盒继续存完余料");
        check(count(inventory, Items.STONE) == 0, "所有目标材料存完");
        check(count(inventory, Items.DIRT) == 24 * 64, "无关物品未移动");
        check(scanned[0] > 256, "扫描超过旧阈值仍正常完成");
        check(clicks[0] < 36, "只对目标槽发起有限次真实操作");

        // 合法的多次开盒没有共享累计上限。
        for (int i = 0; i < 300; i++) {
            inventory.setItem(24, new ItemStack(Items.STONE));
            drain(session, new SimpleContainer(27), slots, scanned, clicks);
        }
        check(count(inventory, Items.STONE) == 0, "跨越三百个菜单也不会触发累计操作中断");
    }

    private static void drain(TransferSession session, SimpleContainer box, List<Slot> slots, int[] scanned, int[] clicks) {
        session.bind(box);
        // 此预算仅用于让测试在回归出错时及时失败，不参与生产取物逻辑。
        for (int step = 0; step < 80; step++) {
            var result = session.transferNext(slots, slot -> {
                scanned[0]++;
                return slot.getItem().is(Items.STONE);
            }, slot -> {
                clicks[0]++;
                int before = slot.getItem().getCount();
                ItemStack remaining = box.addItem(slot.getItem().copy());
                slot.set(remaining);
                return before - remaining.getCount();
            });
            if (result == TransferSession.Result.EXHAUSTED) return;
        }
        throw new AssertionError("存盒扫描没有收敛");
    }

    private static void rejectionAndRollback() {
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STONE, 64));
        Slot slot = slots(inventory).getFirst();
        TransferSession session = new TransferSession();
        Object menu = new Object();
        session.bind(menu);
        check(session.attempt(slot, ignored -> 0) == TransferSession.Result.UNCHANGED, "拒绝移动被识别");
        check(!session.canAttempt(slot), "拒绝后不重复同一槽");
        session.bind(menu);
        check(!session.canAttempt(slot), "同一菜单绑定保留无进展记录");
        session.bind(new Object());
        check(session.canAttempt(slot), "换盒后重新判断同一个玩家槽");
        check(session.attempt(slot, source -> { source.getItem().shrink(4); return 4; }) == TransferSession.Result.MOVED,
                "部分移动视为进展");
        check(session.canAttempt(slot), "来源数量下降可以继续");
        slot.set(new ItemStack(Items.STONE, 64));
        check(!session.canAttempt(slot), "服务端回滚到原数量时停止反复点击");
        slot.set(new ItemStack(Items.DIRT, 64));
        check(session.canAttempt(slot), "槽位内容变化后重新判断");
        session.reset();
        slot.set(new ItemStack(Items.STONE, 64));
        check(session.canAttempt(slot), "新一轮任务清空旧快照");
    }

    private static List<Slot> slots(SimpleContainer container) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            Slot slot = new Slot(container, i, 0, 0);
            slot.index = i;
            slots.add(slot);
        }
        return slots;
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) count += container.getItem(i).getCount();
        return count;
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
        checks++;
    }
}
