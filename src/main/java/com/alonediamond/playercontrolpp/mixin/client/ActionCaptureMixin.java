package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.record.ActionCapture;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 从 vanilla 调用点直接抓离散动作：
 *
 * <ul>
 *   <li>{@code LocalPlayer.drop(boolean)}：Q 与 Ctrl+Q 都走这里，整组丢时参数为
 *       {@code true}。本地单人/多人均会调用；创造模式飞行中丢物品也走它。</li>
 * </ul>
 *
 * <p>聊天与命令走 {@code ChatScreenMixin}（{@code handleChatInput} 是两个入口共同的
 * 收敛点）；快捷栏与主副手交换由 {@code ActionRecorder} 轮询差分抓取。
 * 每个注入器 {@code require = 0}：目标方法在某个版本节点改名时只会静默不注入，
 * 不会让整个 mixin 配置崩溃。
 */
@Mixin(LocalPlayer.class)
public abstract class ActionCaptureMixin {

    @Inject(method = "drop", at = @At("HEAD"), require = 0)
    private void playercontrolpp$onDrop(boolean dropAll, CallbackInfoReturnable<?> cir) {
        ActionCapture.onDrop(dropAll);
    }
}
