package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.NbtCompat;

import net.minecraft.nbt.CompoundTag;

/**
 * 一个离散动作事件。与 {@link RecordedSegment} 相反：段描述"连续若干 tick 的持续输入"，
 * 事件描述"某一 tick 发生一次的事"——丢东西、切快捷栏、主副手交换、发言、执行命令。
 *
 * <p>回放侧按 {@link #tick} 与回放的绝对 tick 对齐触发，见 {@code InputPlayer}。
 * 事件顺序就是录制顺序（同一 tick 内多个事件保持追加序），所以列表本身有序，
 * 回放扫描只需前进指针、不用排序。
 */
public class RecordedAction {

    /** 丢一件手持物品（Q）。 */
    public static final String TYPE_DROP = "drop";
    /** 丢整组手持物品（Ctrl+Q）。 */
    public static final String TYPE_DROP_ALL = "drop_all";
    /** 切换快捷栏选中格（1-9 / 滚轮）。{@link #slot} = 0..8。 */
    public static final String TYPE_HOTBAR = "hotbar";
    /** 主副手交换（F）。 */
    public static final String TYPE_SWAP_HANDS = "swap_hands";
    /** 发言。{@link #text} = 消息原文。 */
    public static final String TYPE_CHAT = "chat";
    /** 执行命令。{@link #text} = 带斜杠的原文，发送时剥掉。 */
    public static final String TYPE_COMMAND = "command";

    /** 录制内的绝对 tick，与 {@code InputRecorder.getTick()} 同源。 */
    public int tick;
    /** {@link TYPE_DROP} 等常量之一。 */
    public String type;
    /** 快捷栏槽位（仅 {@link #TYPE_HOTBAR} 用）。 */
    public int slot;
    /** 消息或命令原文（仅 CHAT / COMMAND 用）。 */
    public String text;

    public RecordedAction() {}

    public RecordedAction(int tick, String type) {
        this(tick, type, 0, null);
    }

    public RecordedAction(int tick, String type, int slot) {
        this(tick, type, slot, null);
    }

    public RecordedAction(int tick, String type, String text) {
        this(tick, type, 0, text);
    }

    public RecordedAction(int tick, String type, int slot, String text) {
        this.tick = tick;
        this.type = type;
        this.slot = slot;
        this.text = text;
    }

    /** 动作捕获用：tick 由 {@code InputRecorder.addAction} 统一打上。 */
    public RecordedAction(String type, String text) {
        this(0, type, 0, text);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("t", tick);
        tag.putString("ty", type);
        if (slot != 0) {
            tag.putInt("sl", slot);
        }
        if (text != null && !text.isEmpty()) {
            tag.putString("tx", text);
        }
        return tag;
    }

    public static RecordedAction fromNbt(CompoundTag tag) {
        RecordedAction action = new RecordedAction();
        action.tick = NbtCompat.getInt(tag, "t", 0);
        action.type = NbtCompat.getString(tag, "ty", "");
        action.slot = NbtCompat.getInt(tag, "sl", 0);
        action.text = NbtCompat.getString(tag, "tx", null);
        return action;
    }
}
