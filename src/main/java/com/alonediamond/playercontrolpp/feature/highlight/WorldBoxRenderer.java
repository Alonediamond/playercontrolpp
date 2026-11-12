package com.alonediamond.playercontrolpp.feature.highlight;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

//#if MC >= 260200
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
//#endif
//#if MC >= 260300
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import com.mojang.renderpearl.api.commands.RenderPass;
//#endif
//#if MC >= 12111
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
//#if MC < 260200
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//#endif

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 在世界里画半透明实心方框。
 *
 * <p>本类是模组自己的世界内方块渲染器，<b>不经过 malilib 的任何画方块函数</b>：
 * 顶点自己生成，变换矩阵自己设置，提交也走原版自己的接口。因此颜色、透明度、外扩量、
 * 层次（光晕 + 实体）都能直接改，后续要给别的功能（比如标记点、区域）复用也只需要调
 * {@link #renderBoxes}。
 *
 * <h3>坐标与矩阵</h3>
 * 世界渲染的几何必须是<b>相机相对坐标</b>，模型视图矩阵只放相机反向旋转
 * （原版 {@code Camera.getViewRotationMatrix} 就是 {@code rotation().conjugate(...)}），
 * 投影矩阵负责透视。所以这里传进顶点的是 {@code 方块坐标 - 相机坐标}，
 * 矩阵里没有平移。
 *
 * <p>为什么必须自己设矩阵：{@code LevelRenderer.renderLevel} 在方法开头 push 相机矩阵、
 * 在<b>末尾</b>才 pop，而我们注入在 {@code TAIL}（pop 之后）。那一刻栈上是单位矩阵，
 * 直接用会导致方框钉在屏幕上、跟着视角转。
 *
 * <h3>提交接口的两个时代</h3>
 * <ul>
 *   <li><b>1.21.1 – 26.1.2</b>：{@code MultiBufferSource.BufferSource} + {@code RenderType}。
 *       原版的 {@code debugQuads()} 正好就是我们要的管线
 *       （POSITION_COLOR + QUADS + 半透明混合 + 不写深度 + 不剔除）。</li>
 *   <li><b>26.2 起</b>：{@code MultiBufferSource} 被移除，改用它内部的
 *       {@code StagedVertexBuffer} + {@code PreparedRenderType}；26.3 再把
 *       {@code drawFromBuffer} 拆成需要显式 {@code RenderPass} 的重载。</li>
 * </ul>
 *
 * <p>想换成自定义 {@code RenderPipeline}（比如穿墙、发光、描边宽度）时，只需替换
 * {@link #worldType()}：26.x 里 {@code RenderType.create} 是包内可见的，需要配一个
 * {@code @Invoker} mixin 才能拿到；<b>当前选择原版管线是因为参数完全一致，
 * 自己注册一条一模一样的只会多一份维护成本。</b>
 */
public final class WorldBoxRenderer {
    /** 实体框相对方块表面的外扩量：太小会和箱子贴图打架，太大就不贴边了。 */
    private static final float BOX_INFLATE = 0.004F;
    /** 外面那层"光晕"的外扩量与透明度比例：让边缘有个柔和的过渡。 */
    private static final float HALO_INFLATE = 0.020F;
    private static final float HALO_ALPHA_SCALE = 0.35F;

    private WorldBoxRenderer() {}

    /**
     * 画一组方块。
     *
     * @param boxes   方块坐标
     * @param red     0–1
     * @param green   0–1
     * @param blue    0–1
     * @param alpha   0–1，实体框的不透明度；光晕层按 {@link #HALO_ALPHA_SCALE} 变淡
     */
    public static void renderBoxes(Collection<BlockPos> boxes, float red, float green, float blue, float alpha) {
        if (boxes.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;

        Camera camera = mainCamera(mc);
        // 1.21.11 之前这个访问器叫 getPosition()，之后才改名为 position()。
        //#if MC >= 12111
        Vec3 cam = camera.position();
        //#else
        //$$ Vec3 cam = camera.getPosition();
        //#endif

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.identity();
        stack.rotate(camera.rotation().conjugate(new Quaternionf()));
        // 1.21.1 的 getModelViewMatrix() 返回一份独立快照，要把栈同步过去；
        // 1.21.4 起返回栈本身，且这个方法被删了。
        //#if MC < 12104
        //$$ RenderSystem.applyModelViewMatrix();
        //#endif
        try {
            //#if MC >= 260200
            submitStaged(mc, boxes, cam, red, green, blue, alpha);
            //#else
            //$$ submitBatched(mc, boxes, cam, red, green, blue, alpha);
            //#endif
        } finally {
            stack.popMatrix();
            //#if MC < 12104
            //$$ RenderSystem.applyModelViewMatrix();
            //#endif
        }
    }

    // ---- 顶点生成（与版本无关）----

    /** 每个方块画两层：一层淡光晕 + 一层实体，边缘看起来不会那么生硬。 */
    private static void emitBox(VertexConsumer vc, BlockPos pos, Vec3 cam,
                                float red, float green, float blue, float alpha) {
        emitBox(vc, pos, cam, red, green, blue, alpha * HALO_ALPHA_SCALE, HALO_INFLATE);
        emitBox(vc, pos, cam, red, green, blue, alpha, BOX_INFLATE);
    }

    /** 发出一个立方体的 6 个面（每个面 4 个顶点，QUADS 模式）。坐标系是相机相对的。 */
    private static void emitBox(VertexConsumer vc, BlockPos pos, Vec3 cam,
                                float red, float green, float blue, float alpha, float inflate) {
        float x0 = (float) (pos.getX() - cam.x - inflate);
        float y0 = (float) (pos.getY() - cam.y - inflate);
        float z0 = (float) (pos.getZ() - cam.z - inflate);
        float x1 = (float) (pos.getX() + 1.0 - cam.x + inflate);
        float y1 = (float) (pos.getY() + 1.0 - cam.y + inflate);
        float z1 = (float) (pos.getZ() + 1.0 - cam.z + inflate);

        quad(vc, red, green, blue, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // -X
        quad(vc, red, green, blue, alpha, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // +X
        quad(vc, red, green, blue, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // -Y
        quad(vc, red, green, blue, alpha, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // +Y
        quad(vc, red, green, blue, alpha, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // -Z
        quad(vc, red, green, blue, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // +Z
    }

    private static void quad(VertexConsumer vc, float red, float green, float blue, float alpha,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vc.addVertex(ax, ay, az).setColor(red, green, blue, alpha);
        vc.addVertex(bx, by, bz).setColor(red, green, blue, alpha);
        vc.addVertex(cx, cy, cz).setColor(red, green, blue, alpha);
        vc.addVertex(dx, dy, dz).setColor(red, green, blue, alpha);
    }

    // ---- 提交：1.21.1 – 26.1.2 ----

    //#if MC < 260200
    //$$ private static void submitBatched(Minecraft mc, Collection<BlockPos> boxes, Vec3 cam,
    //$$                                     float red, float green, float blue, float alpha) {
    //$$     MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
    //$$     RenderType type = worldType();
    //$$     VertexConsumer vc = buffers.getBuffer(type);
    //$$     for (BlockPos pos : boxes) {
    //$$         emitBox(vc, pos, cam, red, green, blue, alpha);
    //$$     }
    //$$     // 我们自己结束这一批；这批不会和其它 RenderType 混在一起。
    //$$     buffers.endBatch(type);
    //$$ }
    //#endif

    // ---- 提交：26.2+ ----

    //#if MC >= 260200
    private static void submitStaged(Minecraft mc, Collection<BlockPos> boxes, Vec3 cam,
                                     float red, float green, float blue, float alpha) {
        RenderType type = worldType();
        // prepare() 会把此刻的模型视图矩阵写进 DynamicTransforms，所以必须在设置矩阵之后调用。
        PreparedRenderType prepared = type.prepare();
        StagedVertexBuffer buffer = mc.gameRenderer.renderBuffers().stagedVertexBuffer();
        StagedVertexBuffer.Draw draw = buffer.appendDraw(type.format(), type.primitiveTopology());
        VertexConsumer vc = buffer.getVertexBuilder(draw);
        for (BlockPos pos : boxes) {
            emitBox(vc, pos, cam, red, green, blue, alpha);
        }
        buffer.upload();
        StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draw);
        if (info == null) return;

        //#if MC >= 260300
        //$$ // 26.3 把 RenderPass 的责任交给了调用方：自己开一个画到主渲染目标的 pass。
        //$$ RenderTarget target = mc.gameRenderer.mainRenderTarget();
        //$$ try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
        //$$         () -> "playercontrolpp:container_highlight",
        //$$         target.getColorTextureView(), Optional.empty(),
        //$$         target.getDepthTextureView(), OptionalDouble.empty())) {
        //$$     prepared.drawFromBuffer(info, pass);
        //$$ }
        //#else
        prepared.drawFromBuffer(info);
        //#endif
    }
    //#endif

    /** 高亮用的渲染类型，由 {@link HighlightPipelines} 提供（自定义管线）。 */
    private static RenderType worldType() {
        return HighlightPipelines.boxType();
    }

    /** 26.2 起访问器从 getMainCamera() 改名成 mainCamera()。 */
    private static Camera mainCamera(Minecraft mc) {
        //#if MC >= 260200
        return mc.gameRenderer.mainCamera();
        //#else
        //$$ return mc.gameRenderer.getMainCamera();
        //#endif
    }
}
