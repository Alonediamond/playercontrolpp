package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.InputCompat;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

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
    /** 「录制中」ActionBar 提示的间隔 tick 数。 */
    private static final int STATUS_INTERVAL = 40;

    private boolean recording;
    private int totalTicks;

    // Recording metadata
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private String dimension;
    private String recordingName;

    private final List<RecordedSegment> segments = new ArrayList<>();
    /** 正在延长的那一段；还没进 {@link #segments}。 */
    private RecordedSegment currentSegment;

    private final List<PositionKeyframe> keyframes = new ArrayList<>();

    private int actionBarCounter;

    public boolean isRecording() { return recording; }

    public void startRecording(String name) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        this.recordingName = name;
        this.totalTicks = 0;
        this.actionBarCounter = 0;

        this.segments.clear();
        this.keyframes.clear();
        this.currentSegment = null;

        this.startX = player.getX();
        this.startY = player.getY();
        this.startZ = player.getZ();
        this.startYaw = player.getYRot();
        this.startPitch = player.getXRot();
        this.dimension = client.level.dimension().identifier().toString();
        this.recording = true;

        MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.started");
    }

    public RecordingFile stopRecording() {
        recording = false;

        // 把还在延长中的那一段提交掉。
        if (currentSegment != null) {
            segments.add(currentSegment);
            currentSegment = null;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.stopped");
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
        // 交出副本，继续录制不会改到已经交出去的东西。
        file.setSegments(new ArrayList<>(segments));
        file.setKeyframes(new ArrayList<>(keyframes));
        return file;
    }

    public void tick(Minecraft client) {
        if (!recording) return;

        LocalPlayer player = client.player;
        if (player == null || player.input == null) return;

        actionBarCounter++;
        if (actionBarCounter % STATUS_INTERVAL == 0) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.active");
        }

        var movementVec = player.input.getMoveVector();
        float fw = movementVec.y;
        float sw = movementVec.x;
        boolean j = InputCompat.isJumping(player);
        boolean sn = InputCompat.isSneaking(player);
        boolean sp = InputCompat.isSprinting(player);
        boolean at = client.options.keyAttack.isDown();
        boolean us = client.options.keyUse.isDown();
        float y = Mth.wrapDegrees(player.getYRot());
        float p = player.getXRot();

        if (currentSegment != null && currentSegment.matches(fw, sw, j, sn, sp, y, p, at, us)) {
            currentSegment.duration++;
        } else {
            if (currentSegment != null) {
                segments.add(currentSegment);
            }
            // 永远新建对象：复用会改写已经进列表的那一段。
            currentSegment = new RecordedSegment(1, fw, sw, j, sn, sp, y, p, at, us);
        }

        totalTicks++;

        if (totalTicks % KEYFRAME_INTERVAL == 0) {
            keyframes.add(new PositionKeyframe(totalTicks, player.getX(), player.getY(), player.getZ()));
        }
    }
}
