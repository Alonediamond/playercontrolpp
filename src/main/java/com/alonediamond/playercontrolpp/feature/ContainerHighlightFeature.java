package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.highlight.WorldBoxRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 在世界里高亮方块，用来指出"这些容器没能缓存成功"。
 *
 * <p>本类只管"哪些方块、亮多久"；怎么画在
 * {@link WorldBoxRenderer}（模组自己的世界渲染器，不经过 malilib）。
 * 渲染入口在 {@code MixinLevelRenderer}，原因见那个类。
 *
 * <p>高亮是"一次性"的：持续指定 tick 数后自动消失。
 */
public final class ContainerHighlightFeature implements ClientFeature {
    /** 高亮颜色（橙色）。 */
    private static final float RED = 1.0F;
    private static final float GREEN = 0.45F;
    private static final float BLUE = 0.10F;
    /** 实心框的不透明度：看得清，又不遮住箱子本身的贴图。 */
    private static final float ALPHA = 0.35F;

    private static final ContainerHighlightFeature INSTANCE = new ContainerHighlightFeature();

    /** 当前要高亮的方块；去重，避免同一个位置被描两遍。 */
    private final Set<BlockPos> positions = new LinkedHashSet<>();
    /** 剩余显示 tick。 */
    private int remainingTicks;

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

    /** 清掉当前高亮（重新运行缓存任务、切换世界时用）。 */
    public void clear() {
        positions.clear();
        remainingTicks = 0;
    }

    /**
     * tick 只用来让高亮到期消失。
     *
     * <p>刻意<b>不</b>覆写 {@link ClientFeature#isActive()}：高亮不是一项"正在运行的功能"，
     * 不该让备货、续料、容器缓存之间的互斥判断把玩家挡在门外。
     */
    @Override
    public void onClientTick(Minecraft mc) {
        if (remainingTicks <= 0) return;
        if (--remainingTicks <= 0) positions.clear();
    }

    @Override
    public void onWorldChange() {
        clear();
    }

    /** 由 {@code MixinLevelRenderer} 在渲染入口末尾调用。 */
    public void renderWorld() {
        if (positions.isEmpty() || remainingTicks <= 0) return;
        // 拷一份再遍历：渲染期间高亮列表可能被 tick 清空（换维度、超时）。
        WorldBoxRenderer.renderBoxes(new ArrayList<>(positions), RED, GREEN, BLUE, ALPHA);
    }

    /** 配置项读取集中在这里。 */
    public static int configuredTicks() {
        return Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_TICKS.getIntegerValue();
    }
}
