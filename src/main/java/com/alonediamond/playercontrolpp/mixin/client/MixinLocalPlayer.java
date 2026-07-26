package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
