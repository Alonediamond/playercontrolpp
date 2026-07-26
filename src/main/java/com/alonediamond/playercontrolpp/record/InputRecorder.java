package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.InputCompat;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the player's input once per tick, run-length encoded: consecutive ticks with identical
 * input become one {@link RecordedSegment} with a duration counter, and a new segment is only
 * allocated when something changes. Holding W for ten seconds is one segment, not two hundred.
 *
 * <p>A position keyframe is stored every {@link #KEYFRAME_INTERVAL} ticks so playback can tell
 * whether it has drifted off the recorded path.
 */
public class InputRecorder {

    /** Ticks between position keyframes. */
    private static final int KEYFRAME_INTERVAL = 20;
    /** Ticks between "still recording" action bar reminders. */
    private static final int STATUS_INTERVAL = 40;

    private boolean recording;
    private int totalTicks;

    // Recording metadata
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private String dimension;
    private String recordingName;

    private final List<RecordedSegment> segments = new ArrayList<>();
    /** The segment being extended; not yet in {@link #segments}. */
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

        // Commit whatever segment was still being extended.
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
        // Copies, so continuing to record cannot mutate what was handed out.
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
            // Always a fresh object: reusing it would rewrite the segment already in the list.
            currentSegment = new RecordedSegment(1, fw, sw, j, sn, sp, y, p, at, us);
        }

        totalTicks++;

        if (totalTicks % KEYFRAME_INTERVAL == 0) {
            keyframes.add(new PositionKeyframe(totalTicks, player.getX(), player.getY(), player.getZ()));
        }
    }
}
