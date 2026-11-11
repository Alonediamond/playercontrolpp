package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.feature.ContainerHighlightFeature;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在世界渲染结束时画容器高亮。
 *
 * <p>为什么不用 malilib 的 {@code IRenderer.onRenderWorldLast}：26.x 的 malilib 把那个回调
 * 塞进了一个 frame pass 里执行，在里面再起一个 {@code RenderContext} 传送不到屏幕上
 * （断点能进、画面没有）。ChestTracker 与 Litematica 都是直接注入
 * {@code LevelRenderer.renderLevel} 的末尾来画世界内内容的，这里照做。
 *
 * <p>回调只声明 {@link CallbackInfo}，不接任何原方法参数——{@code renderLevel} 的签名
 * 在九个版本之间差别很大，这样一份代码就能编过全部版本。{@code require = 0}：万一某个版本
 * 改了方法名，最坏结果是高亮不显示，而不是让游戏崩在类变换阶段。
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    // 26.2 起这个入口从 renderLevel 改名成 render，方法体没变。
    //#if MC >= 260200
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    //#else
    //$$ @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    //#endif
    private void playercontrolpp$renderContainerHighlights(CallbackInfo ci) {
        ContainerHighlightFeature.getInstance().renderWorld();
    }
}
