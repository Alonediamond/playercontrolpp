package com.alonediamond.playercontrolpp.mixin.compat.baritone;

import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;

import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@link BaritoneIntegration} 的直连实现，由 MixinPlugin 在 Baritone 在场时注入。
 *
 * <p>Baritone 的 API 从 1.11.2（1.21.1）到 1.18.0（26.2）逐版本核对过，签名完全一致，
 * zbaritone / baritone-meteor 等 fork 也只改 ModID 不改 API，无需任何版本分支。
 *
 * <p>方法体捕获 {@link Throwable} 的理由见 {@code LitematicaIntegrationImpl}：fork 的
 * API 漂移会以 {@code Error} 形式出现，要与旧反射实现一样静默降级。
 */
@Mixin(BaritoneIntegration.class)
public abstract class BaritoneIntegrationImpl {

    @Unique
    private static IBaritone primaryBaritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    @Overwrite(remap = false)
    public void pathTo(BlockPos target) {
        try {
            cancelPathing();

            primaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
        } catch (Throwable e) {
            // 兜底：改用执行命令的方式
            try {
                primaryBaritone().getCommandManager().execute(
                        String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ()));
            } catch (Throwable ignored) {
                // API 和命令兜底都失败了，说明这个 Baritone 分支两者都不暴露。刻意保持沉默：
                // 调用方（BaritonePathingController）会发现寻路始终没开始并告知玩家，
                // 那条消息比一段异常栈有用得多。
            }
        }
    }

    /** 取消所有正在进行的 Baritone 寻路与自定义目标。 */
    @Overwrite(remap = false)
    public boolean cancelPathing() {
        try {
            IBaritone baritone = primaryBaritone();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCustomGoalProcess().onLostControl();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 查询 Baritone 当前是否在寻路。 */
    @Overwrite(remap = false)
    public boolean isPathing() {
        try {
            return primaryBaritone().getPathingBehavior().isPathing();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean isBuilderActive() {
        try {
            return primaryBaritone().getBuilderProcess().isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean isBuilderPaused() {
        try {
            return primaryBaritone().getBuilderProcess().isPaused();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean startLitematicaBuild(int schematicIndex) {
        try {
            IBuilderProcess builderProcess = primaryBaritone().getBuilderProcess();
            builderProcess.buildOpenLitematic(schematicIndex);
            // buildOpenLitematic 返回 void 且自己吞掉失败——索引上没有 placement 时它只打一行
            // 日志，进程照旧空闲。判据是调用之后进程是否活动，而它是在客户端线程上同步设置的。
            return builderProcess.isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean resumeBuilder() {
        try {
            primaryBaritone().getBuilderProcess().resume();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean allowsInventory() {
        try {
            return Boolean.TRUE.equals(BaritoneAPI.getSettings().allowInventory.value);
        } catch (Throwable e) {
            return false;
        }
    }
}
