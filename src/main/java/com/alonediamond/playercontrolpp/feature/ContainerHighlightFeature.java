package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.render.WorldBoxRenderer;
import com.alonediamond.playercontrolpp.render.BoxStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 在世界里高亮失败的缓存容器和自动备货当前目标。
 *
 * <p>本类只管"哪些方块、亮多久"；怎么画在
 * {@link WorldBoxRenderer}（模组自己的世界渲染器，不经过 malilib）。
 * 渲染入口在 {@code MixinLevelRenderer}，原因见那个类。
 *
 * <p>失败容器高亮按指定 tick 数自动消失；备货目标由业务每 tick 续期。
 */
public final class ContainerHighlightFeature implements ClientFeature {
    /** 橙色半透明框，保留容器贴图的可见度。 */
    private static final BoxStyle STYLE = new BoxStyle(1.0F, 0.45F, 0.10F, 0.35F);

    private static final ContainerHighlightFeature INSTANCE = new ContainerHighlightFeature();

    /** 当前要高亮的方块；去重，避免同一个位置被描两遍。 */
    private final Set<BlockPos> positions = new LinkedHashSet<>();
    /** 剩余显示 tick。 */
    private int remainingTicks;
    /** 备货方每 tick 续期，停机时即使漏掉显式清理也不会留下旧目标。 */
    private BlockPos gatherTarget;
    private int gatherTargetTicks;

    private ContainerHighlightFeature() {}

    public static ContainerHighlightFeature getInstance() { return INSTANCE; }

    /**
     * 高亮一组方块。
     *
     * @param ticks 持续 tick 数；&lt;= 0 表示关闭高亮
     */
    public void highlight(Collection<BlockPos> blocks, int ticks) {
        positions.clear();
        remainingTicks = 0;
        if (blocks == null || blocks.isEmpty() || ticks <= 0) return;
        for (BlockPos pos : blocks) {
            if (pos != null) positions.add(pos.immutable());
        }
        remainingTicks = ticks;
    }

    /** 清掉失败容器高亮（重新运行缓存任务时用）。 */
    public void clear() {
        positions.clear();
        remainingTicks = 0;
    }

    /** 当前容器或工作站；备货运行时每 tick 调用，传 null 清除。 */
    public void setGatherTarget(BlockPos pos) {
        gatherTarget = pos == null ? null : pos.immutable();
        gatherTargetTicks = pos == null ? 0 : 2;
    }

    public void clearGatherTarget() {
        setGatherTarget(null);
    }

    /**
     * tick 只用来让高亮到期消失。
     *
     * <p>刻意<b>不</b>覆写 {@link ClientFeature#isActive()}：高亮不是一项"正在运行的功能"，
     * 不该让备货、续料、容器缓存之间的互斥判断把玩家挡在门外。
     */
    @Override
    public void onClientTick(Minecraft mc) {
        if (remainingTicks > 0 && --remainingTicks <= 0) positions.clear();
        if (gatherTargetTicks > 0 && --gatherTargetTicks <= 0) gatherTarget = null;
    }

    @Override
    public void onWorldChange() {
        clear();
        clearGatherTarget();
    }

    /** 由 {@code MixinLevelRenderer} 在渲染入口末尾调用。 */
    public void renderWorld() {
        if (!positions.isEmpty() && remainingTicks > 0) {
            // 拷一份再遍历：渲染期间高亮列表可能被 tick 清空（换维度、超时）。
            WorldBoxRenderer.renderBoxes(new ArrayList<>(positions), STYLE);
        }
        if (gatherTarget != null && gatherTargetTicks > 0
                && Configs.BaritoneSettings.GATHER_TARGET_HIGHLIGHT.getBooleanValue()) {
            int color = Configs.BaritoneSettings.GATHER_TARGET_COLOR.getIntegerValue();
            float opacity = Configs.BaritoneSettings.GATHER_TARGET_OPACITY.getIntegerValue() / 100.0F;
            if (opacity <= 0.0F) return;
            BoxStyle style = new BoxStyle(((color >> 16) & 0xFF) / 255.0F,
                    ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, opacity);
            WorldBoxRenderer.renderBoxes(java.util.List.of(gatherTarget), style);
        }
    }

    /** 配置项读取集中在这里。 */
    public static int configuredTicks() {
        return Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_TICKS.getIntegerValue();
    }
}
