package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.config.Configs;

/**
 * 动作捕获 mixin 的落点。mixin 只把「vanilla 刚刚发生了 X」递进来；
 * 记不记、记成什么全在这里裁决：
 *
 * <ul>
 *   <li>只有 {@link InputRecorder} 正在录制时才转发；</li>
 *   <li>对应类别的开关关着就不记；</li>
 *   <li>转发给 {@link InputRecorder#addAction(RecordedAction)}，
 *       那里统一打上当前录制的 tick。</li>
 * </ul>
 *
 * <p>mixin 在未录制时每帧也会进来一次，所以第一条判断必须便宜。
 */
public final class ActionCapture {

    private ActionCapture() {}

    /** Q / Ctrl+Q。vanilla 已权威处理完这次丢掷，我们只是记账。 */
    public static void onDrop(boolean dropAll) {
        InputRecorder recorder = RecordingManager.getInstance().getRecorder();
        if (!recorder.isRecording()) return;
        if (!Configs.Settings.RECORD_DROP.getBooleanValue()) return;

        String type = dropAll ? RecordedAction.TYPE_DROP_ALL : RecordedAction.TYPE_DROP;
        recorder.addAction(new RecordedAction(0, type));
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
