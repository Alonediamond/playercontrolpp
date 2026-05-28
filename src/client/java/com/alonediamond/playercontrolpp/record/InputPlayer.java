package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

public class InputPlayer {

    public enum State { IDLE, MOVING_TO_START, PLAYING, COMPLETED }

    private static final double ARRIVAL_SQ = 0.25;
    private static final double HP_CORRECT_SQ = 0.04; // 0.2 blocks - HP correction threshold

    private RecordingFile recording;
    private State state = State.IDLE;
    private int frameIndex;
    private int playCount;
    private int currentPlay;
    private RecordedFrame currentFrame;

    private float playForward;
    private float playSideways;
    private boolean playJump;
    private boolean playSneak;
    private boolean playSprint;
    private boolean playLeftClick;
    private boolean playRightClick;
    private float playYaw;
    private float playPitch;

    // Sprint release delay (ticks since sprint turned off)
    private int sprintOffTicks;

    public State getState() { return state; }
    public boolean isPlaying() { return state == State.PLAYING || state == State.MOVING_TO_START; }

    public float getForward() { return playForward; }
    public float getSideways() { return playSideways; }
    public boolean getJump() { return playJump; }
    public boolean getSneak() { return playSneak; }
    public boolean getSprint() { return playSprint; }
    public boolean getLeftClick() { return playLeftClick; }
    public boolean getRightClick() { return playRightClick; }
    public float getYaw() { return playYaw; }
    public float getPitch() { return playPitch; }
    public RecordingFile getRecording() { return recording; }

    public void start(RecordingFile rec, int playCount) {
        if (rec == null || rec.getFrames().isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        this.recording = rec;
        this.playCount = playCount;
        this.currentPlay = 0;
        this.frameIndex = 0;
        this.sprintOffTicks = 0;
        beginWalkingToStart(client);
    }

    private void beginWalkingToStart(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || recording == null) return;

        double dx = recording.getStartX() - player.getX();
        double dz = recording.getStartZ() - player.getZ();
        if (dx * dx + dz * dz <= ARRIVAL_SQ) {
            beginPlayback(client);
            return;
        }

        state = State.MOVING_TO_START;
        playForward = 1.0f;
        playSideways = 0;
        playJump = false;
        playSneak = false;
        playSprint = false;
        playLeftClick = false;
        playRightClick = false;
        sprintOffTicks = 0;
        MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.walking");
    }

    private void beginPlayback(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        state = State.PLAYING;
        playForward = 0;
        playSideways = 0;
        frameIndex = 0;
        sprintOffTicks = 0;
        loadFrame(0);
        player.setYaw(recording.getStartYaw());
        player.setHeadYaw(recording.getStartYaw());
        player.setPitch(recording.getStartPitch());
        MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.started");
    }

    public void stop() {
        state = State.IDLE;
        playForward = 0;
        playSideways = 0;
        playJump = false;
        playSneak = false;
        playSprint = false;
        playLeftClick = false;
        playRightClick = false;
        sprintOffTicks = 0;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.options.sprintKey.setPressed(false);
            MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.stopped");
        }
    }

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || recording == null) {
            state = State.IDLE;
            return;
        }

        if (state == State.MOVING_TO_START) {
            double dx = recording.getStartX() - player.getX();
            double dz = recording.getStartZ() - player.getZ();
            double distSq = dx * dx + dz * dz;

            if (distSq <= ARRIVAL_SQ) {
                beginPlayback(client);
                return;
            }

            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            playYaw = MathHelper.wrapDegrees(targetYaw);
            playPitch = 0;
            playForward = 1.0f;
            playSideways = 0;
            playJump = false;
            playSneak = false;
            playSprint = false;
            playLeftClick = false;
            playRightClick = false;
            return;
        }

        if (state != State.PLAYING) return;

        // High-precision: snap to recorded position if deviation is too large
        if (recording.isHighPrecision() && currentFrame != null
                && currentFrame.posX != 0) {
            double pdx = player.getX() - currentFrame.posX;
            double pdy = player.getY() - currentFrame.posY;
            double pdz = player.getZ() - currentFrame.posZ;
            if (pdx*pdx + pdy*pdy + pdz*pdz > HP_CORRECT_SQ) {
                player.setPosition(currentFrame.posX, currentFrame.posY, currentFrame.posZ);
            }
        }

        // Sprint key simulation with release delay
        if (playSprint) {
            client.options.sprintKey.setPressed(true);
            sprintOffTicks = 0;
        } else if (sprintOffTicks < 3) {
            // Hold sprint key off for a few ticks to ensure smooth transition
            client.options.sprintKey.setPressed(false);
            sprintOffTicks++;
        }

        frameIndex++;
        if (frameIndex >= recording.getFrames().size()) {
            currentPlay++;
            if (playCount == 0 || currentPlay < playCount) {
                client.options.sprintKey.setPressed(false);
                beginWalkingToStart(client);
            } else {
                state = State.COMPLETED;
                playForward = 0;
                playSideways = 0;
                playJump = false;
                playSneak = false;
                playSprint = false;
                playLeftClick = false;
                playRightClick = false;
                client.options.sprintKey.setPressed(false);
                MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.completed");
            }
            return;
        }

        loadFrame(frameIndex);
    }

    private void loadFrame(int idx) {
        if (recording == null || idx >= recording.getFrames().size()) return;
        RecordedFrame f = recording.getFrames().get(idx);
        this.currentFrame = f;
        this.playForward = f.movementForward;
        this.playSideways = f.movementSideways;
        this.playJump = f.jump;
        this.playSneak = f.sneak;
        this.playSprint = f.sprint;
        this.playLeftClick = f.leftClick;
        this.playRightClick = f.rightClick;
        this.playYaw = f.yaw;
        this.playPitch = f.pitch;
    }

    public void applyYaw(MinecraftClient client) {
        if (state == State.MOVING_TO_START) {
            ClientPlayerEntity player = client.player;
            if (player != null && recording != null) {
                player.setYaw(MathHelper.wrapDegrees(playYaw));
                player.setHeadYaw(MathHelper.wrapDegrees(playYaw));
            }
            return;
        }
        if (state != State.PLAYING) return;
        ClientPlayerEntity player = client.player;
        if (player == null || currentFrame == null) return;
        player.setYaw(MathHelper.wrapDegrees(playYaw));
        player.setHeadYaw(MathHelper.wrapDegrees(playYaw));
        player.setPitch(playPitch);
    }
}
