package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在世界里给方块描边高亮，用来指出"这些容器没能缓存成功"。
 *
 * <p>ChestTracker 本身只画悬浮名称文字，没有方块描边，所以这里自己画。
 * 真正调用渲染的入口在 {@code MixinLevelRenderer}（{@code LevelRenderer.renderLevel} 末尾），
 * 与 ChestTracker / Litematica 画世界内容的方式一致；原因见那个类。
 *
 * <p>高亮是"一次性"的：持续指定 tick 数后自动消失。
 */
public final class ContainerHighlightFeature implements ClientFeature {

    /** 描边离方块表面略微外扩，避免和方块表面 z-fighting。 */
    private static final float EXPAND = 0.002F;
    /** 线宽。 */
    private static final float LINE_WIDTH = 2.0F;

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

    // ---- 渲染入口（由 MixinLevelRenderer 调用）----

    /** 在世界渲染末尾被调用，把当前所有高亮方块描一遍。 */
    public void renderWorld() {
        if (positions.isEmpty() || remainingTicks <= 0) return;
        // 拷一份再遍历：渲染期间高亮列表可能被 tick 清空（换维度、超时）。
        for (BlockPos pos : new ArrayList<>(positions)) {
            drawOutline(pos);
        }
    }

    /**
     * 画单个方块的描边。
     *
     * <p>1.21.6 起 malilib 的 {@code renderBlockOutline} 不再需要 {@code Minecraft} 参数，
     * 颜色类型也从 {@code util.Color4f} 换成了 {@code util.data.Color4f}。这里就地分支，
     * 其余逻辑只有一份。
     */
    // 1.21.1/1.21.4 只有 util.Color4f 这个重载，而它在新版里标了过时；这是被迫使用旧重载。
    @SuppressWarnings("deprecation")
    private static void drawOutline(BlockPos pos) {
        try {
            //#if MC >= 12106
            // renderThrough = true：走 NO_DEPTH_NO_CULL 管线。高亮框被墙挡住时也看得见，
            // 玩家才知道"还有几个箱子没缓存"，也避开了深度测试把线裁掉的可能。
            RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH,
                    new fi.dy.masa.malilib.util.data.Color4f(1.0F, 0.35F, 0.15F, 0.9F), true);
            //#else
            //$$ RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH,
            //$$         new fi.dy.masa.malilib.util.Color4f(1.0F, 0.35F, 0.15F, 0.9F), Minecraft.getInstance());
            //#endif
        } catch (Throwable ignored) {
            // 渲染路径上的异常绝不能冒到渲染循环里；最坏情况只是这一帧没画出描边。
        }
    }

    /** 配置项读取集中在这里。 */
    public static int configuredTicks() {
        return Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_TICKS.getIntegerValue();
    }
}
