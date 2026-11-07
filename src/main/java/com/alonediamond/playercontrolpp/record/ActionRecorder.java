package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 逐 tick 轮询的差分捕获：主副手物品对与快捷栏选中格。
 *
 * <p>与动作相关的确定性来源放在 {@code ActionCaptureMixin}（drop / 聊天 / 命令直接从
 * vanilla 调用点抓）；这里只抓 mixin 拿不到形状的持续状态：
 *
 * <ul>
 *   <li><b>快捷栏切换</b>：选中槽位变化 = 一条 HOTBAR 事件；</li>
 *   <li><b>主副手交换</b>：主、副手物品对同 tick 都变了，且本次采样中本 tick 没有记到
 *       丢掷。vanilla 的 F 交换只换引用、不动数量；丢掷只清主手一边，两者形状不同。
 *       {@code ActionCaptureMixin} 在判定丢掷时会通知本类，避免把「F 交换后立刻丢」
 *       这类同 tick 罕见事件合并错。</li>
 * </ul>
 *
 * <p>输入驱动的瞬态动作（丢、整组丢、发言、命令）由 mixin 直接喂给
 * {@link InputRecorder}；这里抓不到的种类是刻意放行的，不是遗漏。
 */
public class ActionRecorder {

    private static final int STATUS_INTERVAL = 40;

    private int lastSelectedSlot;
    private Object lastMain;
    private Object lastOff;
    private int actionBarCounter;

    public void reset(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        lastSelectedSlot = InventoryCompat.getSelectedSlot(player.getInventory());
        lastMain = player.getMainHandItem();
        lastOff = player.getOffhandItem();
        actionBarCounter = 0;
    }

    /** 采样一次。由 {@code InputRecorder.tick()} 在确定本 tick 在录制之后调用。 */
    public void tick(Minecraft client, InputRecorder recorder) {
        LocalPlayer player = client.player;
        if (player == null) return;

        int tick = recorder.getTick();

        actionBarCounter++;
        if (actionBarCounter % STATUS_INTERVAL == 0) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.active",
                    recorder.getActionCount());
        }

        if (Configs.Settings.RECORD_HOTBAR.getBooleanValue()) {
            int selected = InventoryCompat.getSelectedSlot(player.getInventory());
            Object main = player.getMainHandItem();
            Object off = player.getOffhandItem();

            if (selected != lastSelectedSlot) {
                recorder.addAction(new RecordedAction(tick, RecordedAction.TYPE_HOTBAR, selected));
                lastSelectedSlot = selected;
            }
            // 主副手引用对都变了：vanilla 的 F 交换只换引用。丢掷/使用只清主手一边，
            // 不会两个引用都变；mixin 报丢掷时已先刷新 lastMain，所以这里天然不会跟丢掷撞车。
            if (main != lastMain && off != lastOff) {
                recorder.addAction(new RecordedAction(tick, RecordedAction.TYPE_SWAP_HANDS));
            }
            lastMain = main;
            lastOff = off;
        }
    }
}
