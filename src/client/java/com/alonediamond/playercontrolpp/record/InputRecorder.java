package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class InputRecorder {

    private boolean recording;
    private boolean highPrecision;
    private final List<RecordedFrame> frames = new ArrayList<>();
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private String dimension;
    private String recordingName;

    public boolean isRecording() { return recording; }
    public boolean isHighPrecision() { return highPrecision; }

    public void startRecording(String name, boolean highPrecision) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        this.recordingName = name;
        this.highPrecision = highPrecision;
        this.frames.clear();
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.stopped");
        }

        RecordingFile file = new RecordingFile();
        file.setName(recordingName);
        file.setStartX(startX);
        file.setStartY(startY);
        file.setStartZ(startZ);
        file.setStartYaw(startYaw);
        file.setStartPitch(startPitch);
        file.setDimension(dimension);
        file.setFrames(new ArrayList<>(frames));
        file.setHighPrecision(highPrecision);
        return file;
    }

    public void tick(MinecraftClient client) {
        if (!recording) return;

        ClientPlayerEntity player = client.player;
        if (player == null || player.input == null) return;

        RecordedFrame frame = new RecordedFrame(
                player.input.movementForward,
                player.input.movementSideways,
                player.input.playerInput.jump(),
                player.input.playerInput.sneak(),
                player.input.playerInput.sprint(),
                client.options.attackKey.isPressed(),
                client.options.useKey.isPressed(),
                MathHelper.wrapDegrees(player.getYaw()),
                player.getPitch()
        );
        if (highPrecision) {
            frame.posX = player.getX();
            frame.posY = player.getY();
            frame.posZ = player.getZ();
        }
        frames.add(frame);
    }
}
