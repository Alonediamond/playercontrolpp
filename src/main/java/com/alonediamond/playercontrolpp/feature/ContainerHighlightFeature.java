package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

//#if MC >= 260000
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
//#endif

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在世界里给方块描边高亮，用来指出"这些容器没能缓存成功"。
 *
 * <p>ChestTracker 本身只画悬浮名称文字，没有方块描边，所以这里自己做。渲染直接借 malilib
 * 的 {@code RenderUtils.renderBlockOutline}——它已经吃掉了 1.21.x 与 26.x 两代渲染管线的差异，
 * 本类只需要处理两处签名差异（见下面的预处理器分支）：
 * <ul>
 *   <li>1.21.6 起 {@code renderBlockOutline} 不再需要 {@code Minecraft} 参数，颜色类型也从
 *       {@code util.Color4f} 换成了 {@code util.data.Color4f}；</li>
 *   <li>26.x 的 {@code IRenderer.onRenderWorldLast} 换成了带相机/帧图的一整套参数。</li>
 * </ul>
 *
 * <p>高亮是"一次性"的：指定持续 tick 数后自动消失，不长期占用画面，也不额外提供清除热键。
 */
public final class ContainerHighlightFeature implements IRenderer, ClientFeature {

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

    /** 只在启动时调用一次：把渲染回调挂到 malilib 上。 */
    public static void register() {
        RenderEventHandler.getInstance().registerWorldLastRenderer(INSTANCE);
    }

    /**
     * 高亮一组方块。
     *
     * <p>时长取配置项；配置为 0（或列表为空）时什么都不做。
     */
    public void highlight(Collection<BlockPos> blocks, int ticksFromConfig) {
        if (blocks == null || blocks.isEmpty()) return;
        if (ticksFromConfig <= 0) return;

        positions.clear();
        for (BlockPos pos : blocks) {
            if (pos != null) positions.add(pos.immutable());
        }
        remainingTicks = ticksFromConfig;
    }

    /** 清掉当前高亮（重新运行缓存任务、切换世界时用）。 */
    public void clear() {
        positions.clear();
        remainingTicks = 0;
    }

    /** 每 tick 递减。由 {@link FeatureRegistry} 驱动，保证世界切换时也会被清掉。 */
    public void tick() {
        if (remainingTicks <= 0) return;
        if (--remainingTicks <= 0) {
            positions.clear();
        }
    }

    /**
     * tick 只用来让高亮到期消失。
     *
     * <p>刻意<b>不</b>覆写 {@link ClientFeature#isActive()}：高亮不是一项"正在运行的功能"，
     * 不该让备货、续料、容器缓存之间的互斥判断把玩家挡在门外。
     */
    @Override
    public void onClientTick(Minecraft mc) {
        tick();
    }

    @Override
    public void onWorldChange() {
        clear();
    }

    /** 配置项读取集中在这里，渲染线程之外也能安全调用。 */
    public static int configuredTicks() {
        return Configs.BaritoneSettings.UNCACHED_HIGHLIGHT_TICKS.getIntegerValue();
    }

    // ---- 渲染 ----

    //#if MC >= 260000
    @Override
    public void onRenderWorldLast(RenderTarget target, Matrix4fc modelViewMatrix,
                                  CameraRenderState cameraState, Frustum frustum,
                                  RenderBuffers buffers, GpuBufferSlice fog, Vector4f fogColor,
                                  ProfilerFiller profiler) {
        if (positions.isEmpty() || remainingTicks <= 0) return;
        for (BlockPos pos : new ArrayList<>(positions)) {
            Outline.draw(pos);
        }
    }
    //#else
    //$$ @Override
    //$$ public void onRenderWorldLast(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
    //$$     if (positions.isEmpty() || remainingTicks <= 0) return;
    //$$     for (BlockPos pos : new ArrayList<>(positions)) {
    //$$         Outline.draw(pos);
    //$$     }
    //$$ }
    //#endif

    /**
     * 画出单个方块的描边。
     *
     * <p>单独一个类是为了把"颜色类型/参数个数"这两个版本差异隔离在一处；
     * 逻辑本身在渲染循环里。
     */
    private static final class Outline {
        /** 橙红色，和方块的灰调背景区分度高。 */
        private static final float R = 1.0F;
        private static final float G = 0.35F;
        private static final float B = 0.15F;
        private static final float A = 0.9F;

        private Outline() {}

        static void draw(BlockPos pos) {
            try {
                //#if MC >= 12106
                RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH,
                        new fi.dy.masa.malilib.util.data.Color4f(R, G, B, A));
                //#else
                //$$ RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH,
                //$$         new fi.dy.masa.malilib.util.Color4f(R, G, B, A), Minecraft.getInstance());
                //#endif
            } catch (Throwable ignored) {
                // 渲染路径上的异常绝不能冒到 tick/渲染循环里；最坏情况只是这一帧没画出描边。
            }
        }
    }

    /** 供调试：当前有多少个高亮方块。 */
    public int highlightedCount() {
        return remainingTicks > 0 ? positions.size() : 0;
    }

    /** 便于测试：直接读取内部列表（只读拷贝）。 */
    List<BlockPos> snapshot() {
        return new ArrayList<>(positions);
    }
}
