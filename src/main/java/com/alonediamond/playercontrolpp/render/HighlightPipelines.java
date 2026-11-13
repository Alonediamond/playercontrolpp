package com.alonediamond.playercontrolpp.render;

import com.alonediamond.playercontrolpp.mixin.client.MixinRenderTypeAccessor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.pipeline.BlendFunction;
//$$ import com.mojang.renderpearl.api.pipeline.ColorTargetState;
//$$ import com.mojang.renderpearl.api.pipeline.CompareOp;
//$$ import com.mojang.renderpearl.api.pipeline.DepthStencilState;
//$$ import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//$$ import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//$$ import net.minecraft.client.renderer.BindGroupLayouts;
//#elseif MC >= 260200
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.BindGroupLayouts;
//#elseif MC >= 260000
//$$ import com.mojang.blaze3d.pipeline.BlendFunction;
//$$ import com.mojang.blaze3d.pipeline.ColorTargetState;
//$$ import com.mojang.blaze3d.pipeline.DepthStencilState;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.platform.CompareOp;
//$$ import com.mojang.blaze3d.shaders.UniformType;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//#elseif MC >= 12111
//$$ import com.mojang.blaze3d.pipeline.BlendFunction;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//$$ import com.mojang.blaze3d.shaders.UniformType;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//#endif

//#if MC >= 12111
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//#else
//$$ import net.minecraft.client.renderer.RenderType;
//#endif

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高亮方框用的渲染管线 / 渲染类型（模组自建，不经过 malilib）。
 *
 * <p>这是一份给"在世界里给玩家看东西"准备的公共设施：当前只有容器高亮在用。
 * 以后做备货容器指引之类的内容时，直接拿 {@link #boxType()} 交给
 * {@link WorldBoxRenderer} 或自己的顶点代码提交即可。
 *
 * <p>管线参数固定成"半透明实心框"：
 * <pre>
 * POSITION_COLOR + QUADS + 半透明混合 + 深度测试（不写深度）+ 不剔除
 * </pre>
 * 不写深度是为了不与方块本身的深度冲突、也不把后面的东西挡掉；不剔除是为了从外面
 * 也能看到内壁，看起来像一块磨砂玻璃。
 *
 * <h3>版本差异</h3>
 * {@code RenderPipeline.Builder} 在一年多里换了四套写法，只能一段版本写一份：
 *
 * <table>
 *   <tr><td>26.3</td><td>同 26.2，但相关类都搬到了 {@code com.mojang.renderpearl.api.pipeline}</td></tr>
 *   <tr><td>26.2</td><td>{@code withBindGroupLayout} + {@code withVertexBinding} + {@code withPrimitiveTopology}</td></tr>
 *   <tr><td>26.1.2</td><td>{@code withUniform} + {@code withVertexFormat} + {@code withColorTargetState}</td></tr>
 *   <tr><td>1.21.11</td><td>{@code withBlend} + {@code withDepthTestFunction} + {@code withVertexFormat}</td></tr>
 * </table>
 *
 * <p>1.21.1 – 1.21.10 退回原版 {@code RenderType.debugQuads()}，视觉一致：
 * <ul>
 *   <li>1.21.1 / 1.21.4 还没有 {@code RenderPipeline}（1.21.5 才引入）；</li>
 *   <li>1.21.6 – 1.21.10 的 {@code RenderType.create} 要传
 *       {@code RenderType.CompositeState}，而那个内部类是 <b>protected</b> 的，
 *       模组连类型名都写不出来。</li>
 * </ul>
 *
 * <h3>为什么要 Mixin</h3>
 * {@code RenderType.create} 在所有受支持版本里都是<b>包内可见</b>，拿到自定义管线后
 * 没法直接包成 {@code RenderType}。{@link MixinRenderTypeAccessor} 用 {@code @Invoker}
 * 把它开放出来；1.21.11 之前用不到它，Mixin 插件按版本跳过注入。
 */
public final class HighlightPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerControlpp/Highlight");

    /** 首次用到时才创建：建 RenderType 会碰渲染线程的状态，别放在类初始化里。 */
    private static RenderType boxType;

    private HighlightPipelines() {}

    /** 半透明实心框的渲染类型。 */
    public static synchronized RenderType boxType() {
        if (boxType == null) {
            boxType = createBoxType();
        }
        return boxType;
    }

    private static RenderType createBoxType() {
        //#if MC >= 12111
        // 1.21.11 起 RenderType 由 RenderSetup 描述；create 是包内方法，走 @Invoker。
        try {
            return MixinRenderTypeAccessor.playercontrolpp$create(
                    "playercontrolpp:highlight_box",
                    RenderSetup.builder(buildPipeline()).createRenderSetup());
        } catch (Throwable t) {
            // 自建类型没建成也不该让渲染循环炸掉：退回原版同参数的类型，只是少了自定义的空间。
            LOGGER.warn("自建高亮渲染管线不可用，退回原版 debugQuads：{}", t.toString());
            return RenderTypes.debugQuads();
        }
        //#else
        //$$ // 老版本没有可用的 RenderPipeline，用原版同参数的类型。
        //$$ return RenderType.debugQuads();
        //#endif
    }

    //#if MC >= 12111
    /** 四个版本区间各写一份，参数完全一致。 */
    private static RenderPipeline buildPipeline() {
        //#if MC >= 260300
        //$$ return RenderPipeline.builder()
        //$$         .withLocation("playercontrolpp:highlight_box")
        //$$         .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        //$$         .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        //$$         .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        //$$         .withVertexShader("core/position_color")
        //$$         .withFragmentShader("core/position_color")
        //$$         .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        //$$         .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        //$$         .withPrimitiveTopology(PrimitiveTopology.QUADS)
        //$$         .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        //$$         .withCull(false)
        //$$         .build();
        //#elseif MC >= 260200
        // 26.2 起改用 bind group + 顶点绑定描述管线，深度比较也换成了反向深度。
        return RenderPipeline.builder()
                .withLocation("playercontrolpp:highlight_box")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withCull(false)
                .build();
        //#elseif MC >= 260000
        //$$ return RenderPipeline.builder()
        //$$         .withLocation("playercontrolpp:highlight_box")
        //$$         .withVertexShader("core/position_color")
        //$$         .withFragmentShader("core/position_color")
        //$$         .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        //$$         .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        //$$         .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        //$$         .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        //$$         .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
        //$$         .withCull(false)
        //$$         .build();
        //#else
        //$$ return RenderPipeline.builder()
        //$$         .withLocation("playercontrolpp:highlight_box")
        //$$         .withVertexShader("core/position_color")
        //$$         .withFragmentShader("core/position_color")
        //$$         .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        //$$         .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        //$$         .withBlend(BlendFunction.TRANSLUCENT)
        //$$         .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        //$$         .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        //$$         .withDepthWrite(false)
        //$$         .withCull(false)
        //$$         .build();
        //#endif
    }
    //#endif
}
