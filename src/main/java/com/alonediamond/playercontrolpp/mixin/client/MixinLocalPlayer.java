package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 全模组唯一的 Mixin。
 *
 * <p>潜行与疾跑是实体<b>状态</b>，不是按键状态：只声明按键不足以让原版在 tick 末尾保持它们，
 * 所以回放和路径疾跑要在 {@code LocalPlayer.tick()} 之后把状态补回去。
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {

    @Inject(method = "tick", at = @At("RETURN"))
    private void playercontrolpp$overrideEntityStates(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;

        InputPlayer playback = RecordingManager.getInstance().getPlayer();
        if (playback.isPlaying()) {
            self.setShiftKeyDown(playback.getSneak());
            if (playback.getSprint() && !self.isSprinting()) {
                self.setSprinting(true);
            }
            return;
        }

        if (RouteFlowRuntime.getInstance().isSprintRequested()) {
            self.setSprinting(true);
        }
    }
}
