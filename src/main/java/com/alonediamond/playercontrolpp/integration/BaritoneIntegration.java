package com.alonediamond.playercontrolpp.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;

public class BaritoneIntegration implements ModIntegration {

    private static final BaritoneIntegration INSTANCE = new BaritoneIntegration();
    private boolean loaded;

    private BaritoneIntegration() {}

    public static BaritoneIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        FabricLoader instance = FabricLoader.getInstance();
        loaded = instance.isModLoaded("baritone") || instance.isModLoaded("zbaritone") || instance.isModLoaded("baritone-meteor");
    }

    private Object getBaritone() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    /** 让 Baritone 开始寻路到指定方块坐标。 */
    public void pathTo(BlockPos target) {
        try {
            cancelPathing();

            Object baritone = getBaritone();
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Object goal = goalClass.getConstructor(BlockPos.class).newInstance(target);

            customGoalProcess.getClass()
                    .getMethod("setGoalAndPath",
                            Class.forName("baritone.api.pathing.goals.Goal"))
                    .invoke(customGoalProcess, goal);

        } catch (Exception e) {
            // 兜底：改用执行命令的方式
            try {
                Object baritone = getBaritone();
                Object cmdManager = baritone.getClass()
                        .getMethod("getCommandManager").invoke(baritone);
                String cmd = String.format("goto %d %d %d",
                        target.getX(), target.getY(), target.getZ());
                cmdManager.getClass()
                        .getMethod("execute", String.class)
                        .invoke(cmdManager, cmd);
            } catch (Exception ignored) {
                // API 和命令兜底都失败了，说明这个 Baritone 分支两者都不暴露。刻意保持沉默：
                // 调用方（BaritonePathingController）会发现寻路始终没开始并告知玩家，
                // 那条消息比一段反射异常栈有用得多。
            }
        }
    }

    /** 取消所有正在进行的 Baritone 寻路与自定义目标。 */
    public boolean cancelPathing() {
        try {
            Object baritone = getBaritone();
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);
            customGoalProcess.getClass().getMethod("onLostControl").invoke(customGoalProcess);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 查询 Baritone 当前是否在寻路。 */
    public boolean isPathing() {
        try {
            Object baritone = getBaritone();
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            Boolean isPathing = (Boolean) pathingBehavior.getClass()
                    .getMethod("isPathing").invoke(pathingBehavior);
            return isPathing != null && isPathing;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 建造进程访问（自动续料用）----

    /**
     * @return Baritone 的 BuilderProcess 当前是否活动（即有 #litematica 建造在跑）。
     *         Baritone 不在或进程空闲时返回 false。
     */
    public boolean isBuilderActive() {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            Boolean active = (Boolean) builderProcess.getClass()
                    .getMethod("isActive").invoke(builderProcess);
            return active != null && active;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return BuilderProcess 当前是否处于暂停（缺料 / 寻路失败 / 目标是液体 / ……）。
     *         进程本身不活动时返回 false。
     */
    public boolean isBuilderPaused() {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            Boolean paused = (Boolean) builderProcess.getClass()
                    .getMethod("isPaused").invoke(builderProcess);
            return paused != null && paused;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 Baritone 的 BuilderProcess 启动一次 Litematica 蓝图建造，等价于在聊天框输入
     * {@code #litematica}（加载了多个投影时是 {@code #litematica <序号>}）。
     *
     * <p>{@code buildOpenLitematic} 返回 {@code void} 且自己吞掉失败——{@code schematicIndex}
     * 上没有 placement 时它只打一行「List of placements has no entry」日志，进程照旧空闲。
     * 所以反射调用成功完全说明不了建造有没有真的开始；判据是调用之后进程是否活动，
     * 而 {@code build()} 是在调用方（客户端）线程上同步设置这个状态的。
     *
     * @param schematicIndex Litematica 已加载投影列表的下标，从 0 开始
     * @return 调用之后 BuilderProcess 是否正在建造某个蓝图
     */
    public boolean startLitematicaBuild(int schematicIndex) {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            builderProcess.getClass()
                    .getMethod("buildOpenLitematic", int.class)
                    .invoke(builderProcess, schematicIndex);
            Boolean active = (Boolean) builderProcess.getClass()
                    .getMethod("isActive").invoke(builderProcess);
            return active != null && active;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清掉 BuilderProcess 的暂停标志，与 Baritone 的 {@code resume} 命令等效。
     *
     * <p>只要进程还活着就优先用它而不是重启建造：重启会重新解析蓝图并把层数计数器重置回
     * {@code startAtLayer}，而 resume 从暂停处接着走，还保留 {@code observedCompleted}。
     *
     * @return 调用是否成功
     */
    public boolean resumeBuilder() {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            builderProcess.getClass().getMethod("resume").invoke(builderProcess);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return Baritone 的 {@code allowInventory} 设置值，默认 {@code false}。
     *         它关闭时建造只放它在快捷栏 9 格里能找到的方块——躺在主背包里的材料它看不见，
     *         所以补到主背包的料并不能解开暂停。
     */
    public boolean allowsInventory() {
        try {
            Object settings = Class.forName("baritone.api.BaritoneAPI")
                    .getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField("allowInventory").get(settings);
            Object value = setting.getClass().getField("value").get(setting);
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            return false;
        }
    }
}
