package com.alonediamond.playercontrolpp.mixin.compat.litematlist;

import com.alonediamond.playercontrolpp.integration.LitematListIntegration;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 观察 LitematList 原材料界面当前的「显示层数」。
 *
 * <p>那个层数是 {@code RawMaterialScreen} 的私有字段，既没有配置项也没有 getter，外部读不到；
 * 而本模组要「跟随 LitematList 的层数」，只能挂在它每次重建配方树的时候
 * （{@code loadOrAnalyze()} 开头，此时字段已是最新值）顺手抄一份出来。
 *
 * <p>字段名与方法名在 1.7.2 的 1.21.10 / 1.21.11 / 26.1.2 / 26.2 四个 jar 上逐版本核对过。
 * 注入刻意写成 {@code require = 0}：将来 LitematList 改了私有方法名时，最坏的结果是观察不到
 * 层数、退回本模组自己的配置，而不是让玩家的游戏崩在类变换阶段。
 */
@Mixin(targets = "com.litematlist.gui.RawMaterialScreen", remap = false)
public abstract class RawMaterialScreenMixin {

    @Shadow(remap = false)
    private int displayDepth;

    @Inject(method = "loadOrAnalyze", at = @At("HEAD"), require = 0)
    private void playercontrolpp$observeDepth(CallbackInfo ci) {
        LitematListIntegration.observePlayerDepth(this.displayDepth);
    }
}
