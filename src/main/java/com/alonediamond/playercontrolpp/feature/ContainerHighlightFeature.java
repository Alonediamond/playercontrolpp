package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

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

    /**
     * 几圈同色描边叠出来的"线宽"。
     *
     * <p>核心渲染管线会忽略 {@code glLineWidth}，单根线始终是 1 像素，在远处几乎看不见；
     * 画几圈略微外扩的同色框，视觉上就有 3~4 像素粗的边框，代价很小。
     */
    private static final float[] OUTLINE_EXPANDS = {0.002F, 0.010F, 0.018F};
    /** 线宽参数：管线不支持时会被忽略，留原值即可。 */
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

    /**
     * 在世界渲染末尾被调用，把当前所有高亮方块描一遍。
     *
     * <h3>模型视图矩阵必须是"相机反向旋转"</h3>
     * malilib 的描边函数内部会把方块坐标减去相机位置（相机相对坐标），原版世界渲染也是这么做的：
     * 几何数据是相机相对的，模型视图栈里只放相机的反向旋转，投影矩阵负责透视。
     *
     * <p>所以这里既不能保留栈上原有的矩阵（那一份不是这个世界阶段的），也不能压成单位矩阵——
     * 单位矩阵等于"相机永远看向 -Z"，方框会钉在屏幕上的固定位置、随视角一起转，
     * 也就是"描边跟着视角跑"的成因。正确做法是显式放上 {@code camera.rotation()} 的共轭。
     *
     * <p>参考实现（其它模组的世界高亮）在绘制前也只是对栈做 translate/pop，
     * 前提正是栈上已经是这个旋转矩阵。
     */
    public void renderWorld() {
        if (positions.isEmpty() || remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;

        // 26.2 起这个访问器从 getMainCamera() 改名成 mainCamera()。
        //#if MC >= 260200
        Camera camera = mc.gameRenderer.mainCamera();
        //#else
        //$$ Camera camera = mc.gameRenderer.getMainCamera();
        //#endif
        Quaternionf inverseViewRotation = camera.rotation().conjugate(new Quaternionf());

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.identity();
        stack.rotate(inverseViewRotation);
        // 1.21.1 的 getModelViewMatrix() 返回的是一份单独的快照，只有 applyModelViewMatrix()
        // 才会把它和栈同步；1.21.4 起直接返回栈本身，就不再需要这一步了。
        //#if MC < 12104
        //$$ RenderSystem.applyModelViewMatrix();
        //#endif
        try {
            // 拷一份再遍历：渲染期间高亮列表可能被 tick 清空（换维度、超时）。
            for (BlockPos pos : new ArrayList<>(positions)) {
                drawOutline(pos);
            }
        } finally {
            stack.popMatrix();
            //#if MC < 12104
            //$$ RenderSystem.applyModelViewMatrix();
            //#endif
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
            for (float expand : OUTLINE_EXPANDS) {
                //#if MC >= 12106
                // renderThrough = false：正常参与深度测试，挡住的框看不见。
                RenderUtils.renderBlockOutline(pos, expand, LINE_WIDTH,
                        new fi.dy.masa.malilib.util.data.Color4f(1.0F, 0.45F, 0.1F, 1.0F), false);
                //#else
                //$$ RenderUtils.renderBlockOutline(pos, expand, LINE_WIDTH,
                //$$         new fi.dy.masa.malilib.util.Color4f(1.0F, 0.45F, 0.1F, 1.0F), Minecraft.getInstance());
                //#endif
            }
        } catch (Throwable ignored) {
            // 渲染路径上的异常绝不能冒到渲染循环里；最坏情况只是这一帧没画出描边。
        }
    }

    /** 配置项读取集中在这里。 */
    public static int configuredTicks() {
        return Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_TICKS.getIntegerValue();
    }
}
