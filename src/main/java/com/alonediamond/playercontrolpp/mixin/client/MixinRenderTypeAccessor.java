package com.alonediamond.playercontrolpp.mixin.client;

//#if MC >= 12111
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把 {@code RenderType.create} 开放出来，用来给自定义管线建渲染类型。
 *
 * <p>这个方法在所有受支持版本里都是<b>包内可见</b>的，模组拿不到；而自定义管线
 * 必须包成 {@code RenderType} 才能走正常的世界渲染提交。{@code @Invoker} 是 Mixin 的
 * 静态调用器写法，只新增一个转调方法，不改动原方法本身。
 *
 * <p>1.21.11 之前用不到这个入口（{@code HighlightPipelines} 退回原版
 * {@code debugQuads()}），{@code PlayercontrolppMixinPlugin} 会按版本跳过注入，
 * 所以那些版本里这个接口是空的。
 */
@Mixin(RenderType.class)
public interface MixinRenderTypeAccessor {

    //#if MC >= 12111
    @Invoker("create")
    static RenderType playercontrolpp$create(String name, RenderSetup setup) {
        throw new AssertionError("MixinRenderTypeAccessor 未被注入");
    }
    //#endif
}
