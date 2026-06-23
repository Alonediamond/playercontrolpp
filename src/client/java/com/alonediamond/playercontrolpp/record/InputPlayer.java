package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * Plays back recorded input by iterating through RLE-compressed segments.
 * Each segment holds an input state (forward, sideways, jump, etc.) and a
 * duration. The segment is applied for duration ticks before advancing.
 *
 * HP mode monitors PositionKeyframes and corrects the player position if
 * drift exceeds 0.2 blocks.
 *
 * State flow: IDLE → MOVING_TO_START (walk to recorded start) → PLAYING → COMPLETED/loop
 */
public class InputPlayer {

    public enum State { IDLE, MOVING_TO_START, PLAYING, COMPLETED }

    /** Arrival threshold squared (0.5 blocks) for walking to start position. */
    private static final double ARRIVAL_SQ = 0.25;
    /** HP correction threshold squared (0.2 blocks). */
    private static final double HP_CORRECT_SQ = 0.04;

    private RecordingFile recording;
    private State state = State.IDLE;
    private int playCount;       // 0 = infinite loop, N = play N times
    private int currentPlay;     // how many times we've played so far

    // Segment-based playback (RLE decompression at runtime)
    private List<RecordedSegment> segments;
    private int segmentIndex;    // which segment we're currently playing
    private int segmentTick;     // how many ticks we've been in this segment
    private int totalTick;       // absolute tick counter (for keyframe alignment)

    private RecordedSegment currentSegment;

    // HP position-drift correction via keyframes
    private List<PositionKeyframe> keyframes;
    private int keyframeIndex;   // next keyframe to check

    // Output values read each tick by MixinKeyboardInput and ClientEventHandler
    private float playForward;
    private float playSideways;
    private boolean playJump;
    private boolean playSneak;
    private boolean playSprint;
    private boolean playLeftClick;
    private boolean playRightClick;
    private float playYaw;
    private float playPitch;

    /** When sprint transitions from on to off, hold the key released for 3 ticks
     *  to ensure the server registers the state change (smoother transition). */
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

    public void start(RecordingFile indexRec, int playCount) {
        if (indexRec == null) return;

        // Load full recording data on demand
        RecordingFile full = RecordingManager.getInstance().loadRecordingFile(indexRec.getId());
        if (full == null || full.getSegments().isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        this.recording = full;
        this.segments = full.getSegments();
        this.keyframes = full.getKeyframes();
        this.playCount = playCount;
        this.currentPlay = 0;
        this.sprintOffTicks = 0;
        this.keyframeIndex = 0;
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
        if (player == null || segments.isEmpty()) return;

        state = State.PLAYING;
        segmentIndex = 0;
        segmentTick = 0;
        totalTick = 0;
        sprintOffTicks = 0;
        keyframeIndex = 0;

        loadSegment(0);
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
        if (player == null || recording == null || segments == null) {
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

        // HP: check keyframe position correction (always active)
        if (keyframes != null && keyframeIndex < keyframes.size()) {
            PositionKeyframe kf = keyframes.get(keyframeIndex);
            if (totalTick >= kf.tick) {
                double pdx = player.getX() - kf.x;
                double pdy = player.getY() - kf.y;
                double pdz = player.getZ() - kf.z;
                if (pdx * pdx + pdy * pdy + pdz * pdz > HP_CORRECT_SQ) {
                    player.setPosition(kf.x, kf.y, kf.z);
                }
                keyframeIndex++;
            }
        }

        // Sprint key simulation with release delay
        if (playSprint) {
            client.options.sprintKey.setPressed(true);
            sprintOffTicks = 0;
        } else if (sprintOffTicks < 3) {
            client.options.sprintKey.setPressed(false);
            sprintOffTicks++;
        }

        // Advance within the current segment. Each tick we count up; when we
        // reach the segment's duration, move to the next segment (or loop/stop).
        segmentTick++;
        totalTick++;

        if (segmentTick >= currentSegment.duration) {
            // Current segment exhausted — advance to next
            segmentIndex++;
            if (segmentIndex >= segments.size()) {
                // End of recording — loop or complete
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
            loadSegment(segmentIndex);
        }
    }

    private void loadSegment(int idx) {
        if (segments == null || idx >= segments.size()) return;
        currentSegment = segments.get(idx);
        segmentTick = 0;
        playForward = currentSegment.forward;
        playSideways = currentSegment.sideways;
        playJump = currentSegment.jump;
        playSneak = currentSegment.sneak;
        playSprint = currentSegment.sprint;
        playLeftClick = currentSegment.attack;
        playRightClick = currentSegment.use;
        playYaw = currentSegment.yaw;
        playPitch = currentSegment.pitch;
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
        if (player == null || currentSegment == null) return;
        player.setYaw(MathHelper.wrapDegrees(playYaw));
        player.setHeadYaw(MathHelper.wrapDegrees(playYaw));
        player.setPitch(playPitch);
    }
}
