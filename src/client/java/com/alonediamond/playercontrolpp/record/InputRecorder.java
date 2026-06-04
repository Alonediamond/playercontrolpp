package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Records player input every tick and compresses consecutive identical states
 * using Run-Length Encoding (RLE). Instead of storing one frame per tick,
 * identical consecutive ticks merge into a single RecordedSegment with a
 * duration counter. A new segment is created only when any input field changes.
 *
 * HP mode additionally records PositionKeyframes every 20 ticks for
 * position-drift correction during playback.
 */
public class InputRecorder {

    /** HP mode: record a position keyframe every 20 ticks (1 second). */
    private static final int KEYFRAME_INTERVAL = 20;

    private boolean recording;
    private boolean highPrecision;
    private int totalTicks;

    // Recording metadata
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private String dimension;
    private String recordingName;

    // RLE compression: current pending segment gets duration++ on match,
    // new segment allocated on state change (never reused to avoid aliasing)
    private final List<RecordedSegment> segments = new ArrayList<>();
    private RecordedSegment currentSegment;
    private boolean hasCurrent;

    // HP position keyframes (one every KEYFRAME_INTERVAL ticks)
    private final List<PositionKeyframe> keyframes = new ArrayList<>();

    private int actionBarCounter;

    public boolean isRecording() { return recording; }
    public boolean isHighPrecision() { return highPrecision; }

    public void startRecording(String name, boolean highPrecision) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        this.recordingName = name;
        this.highPrecision = highPrecision;
        this.totalTicks = 0;
        this.actionBarCounter = 0;

        this.segments.clear();
        this.keyframes.clear();
        this.currentSegment = null;
        this.hasCurrent = false;

        this.startX = player.getX();
        this.startY = player.getY();
        this.startZ = player.getZ();
        this.startYaw = player.getYaw();
        this.startPitch = player.getPitch();
        this.dimension = player.getWorld().getRegistryKey().getValue().toString();
        this.recording = true;

        MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.started");
    }

    public RecordingFile stopRecording() {
        recording = false;

        // Finalize last segment
        if (hasCurrent) {
            segments.add(currentSegment);
            hasCurrent = false;
            currentSegment = null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.stopped");
        }

        RecordingFile file = new RecordingFile();
        file.setName(recordingName);
        file.setHighPrecision(highPrecision);
        file.setDurationTicks(totalTicks);
        file.setDimension(dimension);
        file.setStartX(startX);
        file.setStartY(startY);
        file.setStartZ(startZ);
        file.setStartYaw(startYaw);
        file.setStartPitch(startPitch);
        file.setSegments(new ArrayList<>(segments));
        if (highPrecision) {
            file.setKeyframes(new ArrayList<>(keyframes));
        }
        return file;
    }

    public void tick(MinecraftClient client) {
        if (!recording) return;

        ClientPlayerEntity player = client.player;
        if (player == null || player.input == null) return;

        // Action bar refresh every ~2 seconds
        actionBarCounter++;
        if (actionBarCounter % 40 == 0) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.active");
        }

        // Snapshot current input state
        float fw = player.input.movementForward;
        float sw = player.input.movementSideways;
        boolean j = player.input.playerInput.jump();
        boolean sn = player.input.playerInput.sneak();
        boolean sp = player.input.playerInput.sprint();
        boolean at = client.options.attackKey.isPressed();
        boolean us = client.options.useKey.isPressed();
        float y = MathHelper.wrapDegrees(player.getYaw());
        float p = player.getPitch();

        // RLE: if state unchanged, just increment the current segment's duration.
        // If anything changed, finalize the old segment and allocate a new one.
        // A new allocation is mandatory — reusing the old object would corrupt
        // the segment already stored in the list (Java passes by reference).
        if (hasCurrent && currentSegment.matches(fw, sw, j, sn, sp, y, p, at, us)) {
            currentSegment.duration++;
        } else {
            // Finalize previous segment
            if (hasCurrent) {
                segments.add(currentSegment);
            }
            // Always create a new segment — never reuse, or we corrupt
            // the segment just added to the list (Java passes by reference).
            currentSegment = new RecordedSegment(1, fw, sw, j, sn, sp, y, p, at, us);
            hasCurrent = true;
        }

        totalTicks++;

        // HP: record keyframe every KEYFRAME_INTERVAL ticks
        if (highPrecision && totalTicks % KEYFRAME_INTERVAL == 0) {
            keyframes.add(new PositionKeyframe(totalTicks, player.getX(), player.getY(), player.getZ()));
        }
    }
}
