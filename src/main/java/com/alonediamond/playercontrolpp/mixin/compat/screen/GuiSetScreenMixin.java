package com.alonediamond.playercontrolpp.mixin.compat.screen;

import com.alonediamond.playercontrolpp.feature.ContainerGuiSuppressor;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Minecraft.setScreen} 的 26.2 版本：它挪到了 {@code Gui} 类上，
 * 其余机制与 {@code MinecraftSetScreenMixin} 完全一致，由 MixinPlugin 按版本放行。
 */
@Mixin(Gui.class)
public abstract class GuiSetScreenMixin {

    @Shadow
    public abstract void setScreen(Screen screen);

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void playercontrolpp$suppressContainerScreen(Screen screen, CallbackInfo ci) {
        if (!ContainerGuiSuppressor.shouldSuppress(screen)) return;

        // 屏蔽前先把可能残留的旧界面正常关掉（见 MinecraftSetScreenMixin 同款注释）。
        setScreen(null);
        ci.cancel();
    }
}
