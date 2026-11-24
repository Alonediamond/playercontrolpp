package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/** 按录制选项将原版玩家操作转换为离散动作。 */
public final class ActionCapture {

    private ActionCapture() {}

    public static void onPlayerAction(ServerboundPlayerActionPacket.Action action) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) return;
        InputRecorder recorder = RecordingManager.getInstance().getRecorder();
        if (!recorder.isRecording()) return;

        String type = switch (action) {
            case DROP_ITEM -> Configs.Settings.RECORD_DROP.getBooleanValue()
                    ? RecordedAction.TYPE_DROP : null;
            case DROP_ALL_ITEMS -> Configs.Settings.RECORD_DROP.getBooleanValue()
                    ? RecordedAction.TYPE_DROP_ALL : null;
            case SWAP_ITEM_WITH_OFFHAND -> Configs.Settings.RECORD_HOTBAR.getBooleanValue()
                    ? RecordedAction.TYPE_SWAP_HANDS : null;
            default -> null;
        };
        if (type != null) {
            // 同一 tick 内切槽后马上丢掷或换手，回放时也要先切槽。
            recorder.captureHotbar(client.player);
            recorder.addAction(new RecordedAction(0, type));
        }
    }

    /** 回车提交的聊天/命令原文（命令带斜杠）。 */
    public static void onChatInput(String input) {
        if (input == null || input.isEmpty()) return;

        InputRecorder recorder = RecordingManager.getInstance().getRecorder();
        if (!recorder.isRecording()) return;
        if (!Configs.Settings.RECORD_CHAT.getBooleanValue()) return;

        String type = input.startsWith("/")
                ? RecordedAction.TYPE_COMMAND : RecordedAction.TYPE_CHAT;
        recorder.addAction(new RecordedAction(type, input));
    }
}
