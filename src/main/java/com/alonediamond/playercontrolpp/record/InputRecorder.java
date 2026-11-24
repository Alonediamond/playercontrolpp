package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.InputCompat;
import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * 每 tick 采一次玩家输入，用行程长度编码压缩：连续几 tick 输入完全相同就合成一个
 * {@link RecordedSegment} 加一个持续计数，只有输入变化时才新建一段。
 * 按住 W 十秒是一段，而不是两百段。
 *
 * <p>每 {@link #KEYFRAME_INTERVAL} tick 存一个位置关键帧，回放时用来判断有没有偏离录制路径。
 */
public class InputRecorder {

    /** 两个位置关键帧之间的 tick 数。 */
    private static final int KEYFRAME_INTERVAL = 20;
    private static final int STATUS_INTERVAL_TICKS = 40;

    private boolean recording;
    private int totalTicks;
    private int lastSelectedSlot;

    // Recording metadata
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private int startSlot;
    private String dimension;
    private String recordingName;

    private final List<RecordedSegment> segments = new ArrayList<>();
    /** 正在延长的那一段；还没进 {@link #segments}。 */
    private RecordedSegment currentSegment;

    private final List<PositionKeyframe> keyframes = new ArrayList<>();
    /** 与段同源的离散动作事件（丢、换手、发言、命令…），按 tick 升序。 */
    private final List<RecordedAction> actions = new ArrayList<>();

    public boolean isRecording() { return recording; }

    /** @return 至今记到的离散动作数；给「录制中」状态栏用。 */
    public int getActionCount() { return actions.size(); }

    /**
     * 开始录制。
     *
     * @return 真的开始了才返回 {@code true}；没有玩家、或回放正忙时返回 {@code false}
     *         并给出提示。回放期间不录：回放按下的键会被当成玩家输入录进去。
     */
    public boolean startRecording(String name) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (recording || player == null || client.level == null || player.isDeadOrDying()) return false;
        if (RecordingManager.getInstance().getPlayer().isBusy()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.playback_active");
            return false;
        }

        this.recordingName = name;
        this.totalTicks = 0;

        this.segments.clear();
        this.keyframes.clear();
        this.actions.clear();
        this.currentSegment = null;

        this.startX = player.getX();
        this.startY = player.getY();
        this.startZ = player.getZ();
        this.startYaw = player.getYRot();
        this.startPitch = player.getXRot();
        this.lastSelectedSlot = InventoryCompat.getSelectedSlot(player.getInventory());
        this.startSlot = Configs.Settings.RECORD_HOTBAR.getBooleanValue()
                ? lastSelectedSlot : RecordingFile.NO_START_SLOT;
        this.dimension = client.level.dimension().identifier().toString();
        this.recording = true;

        MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.started");
        return true;
    }

    public RecordingFile stopRecording() {
        return stopRecording(true);
    }

    RecordingFile stopRecording(boolean notify) {
        recording = false;

        // 把还在延长中的那一段提交掉。
        if (currentSegment != null) {
            segments.add(currentSegment);
            currentSegment = null;
        }

        Minecraft client = Minecraft.getInstance();
        // 末尾补一个关键帧：循环模式的回程要靠它知道录制结束时的位置。
        if (totalTicks > 0 && client.player != null
                && (keyframes.isEmpty()
                    || keyframes.get(keyframes.size() - 1).tick != totalTicks)) {
            LocalPlayer end = client.player;
            keyframes.add(new PositionKeyframe(totalTicks, end.getX(), end.getY(), end.getZ()));
        }

        if (notify && client.player != null) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.stopped");
        }

        // 停止热键可能在下一次采样前到达，最后收到的动作放进最后一个可回放 tick。
        for (int i = actions.size() - 1; i >= 0 && actions.get(i).tick > totalTicks; i--) {
            actions.get(i).tick = totalTicks;
        }
        RecordingFile file = new RecordingFile();
        file.setName(recordingName);
        file.setDurationTicks(totalTicks);
        file.setDimension(dimension);
        file.setStartX(startX);
        file.setStartY(startY);
        file.setStartZ(startZ);
        file.setStartYaw(startYaw);
        file.setStartPitch(startPitch);
        file.setStartSlot(startSlot);
        // 交出副本，继续录制不会改到已经交出去的东西。
        file.setSegments(new ArrayList<>(segments));
        file.setKeyframes(new ArrayList<>(keyframes));
        file.setActions(new ArrayList<>(actions));
        return file;
    }

    void addAction(RecordedAction action) {
        // 离散动作在 tick 尾部采样前发生；首个回放 tick 从 1 开始。
        action.tick = totalTicks + 1;
        actions.add(action);
    }

    void captureHotbar(LocalPlayer player) {
        if (player == null) return;
        int selected = InventoryCompat.getSelectedSlot(player.getInventory());
        if (selected != lastSelectedSlot && Configs.Settings.RECORD_HOTBAR.getBooleanValue()) {
            addAction(new RecordedAction(0, RecordedAction.TYPE_HOTBAR, selected));
        }
        lastSelectedSlot = selected;
    }

    public void tick(Minecraft client) {
        if (!recording) return;

        LocalPlayer player = client.player;
        if (player == null || player.input == null) return;

        Vec2 movementVec = player.input.getMoveVector();
        // 每类输入各自受「录制功能」开关控制；关掉的类别一律记成中性值，
        // 这样 RLE 段不会因为它而分裂，回放时也不会驱动对应的键。
        boolean move = Configs.Settings.RECORD_MOVEMENT.getBooleanValue();
        float fw = move ? movementVec.y : 0f;
        float sw = move ? movementVec.x : 0f;
        boolean j = move && InputCompat.isJumping(player);
        boolean sn = move && InputCompat.isSneaking(player);
        boolean sp = move && InputCompat.isSprinting(player);
        boolean at = Configs.Settings.RECORD_ATTACK.getBooleanValue()
                && client.options.keyAttack.isDown();
        boolean us = Configs.Settings.RECORD_USE.getBooleanValue()
                && client.options.keyUse.isDown();
        boolean look = Configs.Settings.RECORD_LOOK.getBooleanValue();
        float y = look ? Mth.wrapDegrees(player.getYRot()) : 0f;
        float p = look ? player.getXRot() : 0f;

        if (currentSegment != null && currentSegment.matches(fw, sw, j, sn, sp, y, p, at, us)) {
            currentSegment.duration++;
        } else {
            if (currentSegment != null) {
                segments.add(currentSegment);
            }
            // 永远新建对象：复用会改写已经进列表的那一段。
            currentSegment = new RecordedSegment(1, fw, sw, j, sn, sp, y, p, at, us);
        }

        captureHotbar(player);
        totalTicks++;

        if (totalTicks % KEYFRAME_INTERVAL == 0) {
            keyframes.add(new PositionKeyframe(totalTicks, player.getX(), player.getY(), player.getZ()));
        }
        if (totalTicks % STATUS_INTERVAL_TICKS == 0) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.active", getActionCount());
        }
    }
}
