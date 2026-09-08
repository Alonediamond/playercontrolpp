package com.alonediamond.playercontrolpp.integration;

import net.minecraft.core.BlockPos;

/**
 * Baritone 联动的默认实现（stub）。真正的方法体在
 * {@code mixin/compat/baritone/BaritoneIntegrationImpl} 里，机制同 {@link LitematicaIntegration}。
 */
public class BaritoneIntegration {

    private static final BaritoneIntegration INSTANCE = new BaritoneIntegration();

    private BaritoneIntegration() {}

    public static BaritoneIntegration getInstance() { return INSTANCE; }

    /** Mixin 注入成功时覆写为 {@code true}；未注入即联动未生效。 */
    public boolean isLoaded() { return false; }

    /** 让 Baritone 开始寻路到指定方块坐标。 */
    public void pathTo(BlockPos target) {}

    /** 取消所有正在进行的 Baritone 寻路与自定义目标。 */
    public boolean cancelPathing() { return false; }

    /** 查询 Baritone 当前是否在寻路。 */
    public boolean isPathing() { return false; }

    /** @return BuilderProcess 当前是否活动（即有 #litematica 建造在跑）。 */
    public boolean isBuilderActive() { return false; }

    /** @return BuilderProcess 当前是否处于暂停（缺料 / 寻路失败 / 目标是液体 / ……）。 */
    public boolean isBuilderPaused() { return false; }

    /** 通过 BuilderProcess 启动一次 Litematica 蓝图建造，等价于输入 {@code #litematica}。 */
    public boolean startLitematicaBuild(int schematicIndex) { return false; }

    /** 清掉 BuilderProcess 的暂停标志，与 Baritone 的 {@code resume} 命令等效。 */
    public boolean resumeBuilder() { return false; }

    /** @return Baritone 的 {@code allowInventory} 设置值，默认 {@code false}。 */
    public boolean allowsInventory() { return false; }
}
