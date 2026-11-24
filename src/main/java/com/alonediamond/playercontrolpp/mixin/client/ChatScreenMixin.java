package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.record.ActionCapture;

import net.minecraft.client.gui.screens.ChatScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抓聊天与命令。{@code ChatScreen.handleChatInput(String, boolean)} 是回车提交的收敛点：
 * 普通消息与命令（带斜杠）都从它出去，而且它在发送网络包的路径上，单人局域网与
 * 多人服务器行为一致。
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    public abstract String normalizeChatMessage(String input);

    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void playercontrolpp$onChatInput(String input, boolean addToHistory, CallbackInfo ci) {
        ActionCapture.onChatInput(normalizeChatMessage(input));
    }
}
