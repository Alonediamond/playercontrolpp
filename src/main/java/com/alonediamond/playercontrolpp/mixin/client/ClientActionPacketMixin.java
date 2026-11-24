package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.record.ActionCapture;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientActionPacketMixin {

    // 26.3 将丢掷移至游戏模式；普通丢掷和换手发包入口在各版本相同。
    @Inject(method = "send", at = @At("HEAD"))
    private void playercontrolpp$captureAction(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            ActionCapture.onPlayerAction(action.getAction());
        }
    }
}
