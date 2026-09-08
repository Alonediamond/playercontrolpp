package com.alonediamond.playercontrolpp.mixin.compat.screen;

import com.alonediamond.playercontrolpp.feature.ContainerGuiSuppressor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code Minecraft.setScreen} 上吞掉缓存容器功能打开的容器界面（MC &lt; 26.2，
 * {@code setScreen} 还在 Minecraft 类上；26.2 起挪到 {@code Gui}，由
 * {@code GuiSetScreenMixin} 负责，两者由 MixinPlugin 按版本只放行一个）。
 *
 * <p>吞掉的只是「出现」：{@code MenuScreens.fromPacket} 在调 {@code setScreen} 之前
 * 就已把 {@code player.containerMenu} 赋好值，内容包照常进菜单，功能按 menu 判据
 * 照常工作。{@code setScreen(null)}（关界面）永远放行。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {

    /** 只为本 mixin 内部的「先正常关掉旧界面」服务；运行时仅存在于 &lt;26.2。 */
    @Shadow
    public abstract void setScreen(Screen screen);

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void playercontrolpp$suppressContainerScreen(Screen screen, CallbackInfo ci) {
        if (!ContainerGuiSuppressor.shouldSuppress(screen)) return;

        // 屏蔽前先把可能残留的旧界面正常关掉（走完整 setScreen(null)：
        // 调用它的 removed()，也让 ChestTracker 等挂在关闭事件上的钩子正常触发）。
        // null 恒不屏蔽，递归到此为止。
        setScreen(null);
        ci.cancel();
    }
}
