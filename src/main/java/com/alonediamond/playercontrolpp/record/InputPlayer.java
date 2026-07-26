package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Replays recorded input by walking the RLE segment list: each segment carries an input state and
 * a duration, and is held for that many ticks before the next one loads.
 *
 * <pre>
 * IDLE -&gt; LOADING -&gt; MOVING_TO_START -&gt; PLAYING -&gt; COMPLETED (or loop back)
 * </pre>
 *
 * <p>This class presses no keys. It publishes the desired input through its getters and
 * {@code ClientEventHandler} turns that into held keys via {@code SimulatedInput} — which is what
 * keeps playback consistent with the mod's rule that movement is only ever simulated input.
 */
public class InputPlayer {

    public enum State { IDLE, LOADING, MOVING_TO_START, PLAYING, COMPLETED }

    /** Arrival threshold squared (0.5 blocks) for walking to the start position. */
    private static final double ARRIVAL_SQ = 0.25;
    /**
     * Drift past which the optional hard position correction fires, squared (2 blocks).
     *
     * <p>The old value was 0.04 — 0.2 blocks — which normal server movement resolution exceeds
     * almost every time, so the correction ran on essentially every keyframe: a teleport per
     * second. Two blocks means it only steps in on real divergence.
     */
    private static final double DRIFT_CORRECT_SQ = 4.0;
    /** Drift past which the user is told playback has diverged, squared (4 blocks). */
    private static final double DRIFT_WARN_SQ = 16.0;
    /** Ticks the sprint key stays released after sprint turns off, so the server registers it. */
    private static final int SPRINT_RELEASE_TICKS = 3;

    private RecordingFile recording;
    private State state = State.IDLE;
    private int playCount;       // 0 = infinite loop, N = play N times
    private int currentPlay;     // how many times we have played so far

    // Segment-based playback (RLE decompression at runtime)
    private List<RecordedSegment> segments;
    private int segmentIndex;    // which segment is playing
    private int segmentTick;     // ticks spent in this segment
    private int totalTick;       // absolute tick counter, for keyframe alignment

    private RecordedSegment currentSegment;

    // Position keyframes, used to detect drift
    private List<PositionKeyframe> keyframes;
    private int keyframeIndex;   // next keyframe to check
    private boolean driftWarned; // warn once per playback, not once per keyframe

    // Output values read each tick by ClientEventHandler
    private float playForward;
    private float playSideways;
    private boolean playJump;
    private boolean playSneak;
    private boolean playSprint;
    private boolean playLeftClick;
    private boolean playRightClick;
    private float playYaw;
    private float playPitch;

    private int sprintOffTicks;

    public State getState() { return state; }

    /** @return whether playback is actively driving the player. */
    public boolean isPlaying() { return state == State.PLAYING || state == State.MOVING_TO_START; }

    /** @return whether playback is running or about to, including the async load. */
    public boolean isBusy() { return state == State.LOADING || isPlaying(); }

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

    /**
     * Begin playback of the recording described by {@code indexRec}.
     *
     * <p>The bulk data is read on a background thread; playback starts from the callback once it
     * arrives, so pressing Play never stalls the frame.
     */
    public void start(RecordingFile indexRec, int playCount) {
        if (indexRec == null || isBusy()) return;

        this.playCount = playCount;
        this.currentPlay = 0;
        this.sprintOffTicks = 0;
        this.keyframeIndex = 0;
        this.driftWarned = false;
        this.state = State.LOADING;

        RecordingManager.getInstance().loadRecordingFileAsync(indexRec.getId(), this::onLoaded);
    }

    private void onLoaded(RecordingFile full) {
        // The user may have pressed Stop, or left the world, while we were reading.
        if (state != State.LOADING) return;

        Minecraft client = Minecraft.getInstance();
        if (full == null || full.getSegments().isEmpty()) {
            state = State.IDLE;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.recording.corrupt");
            return;
        }
        if (client.player == null) {
            state = State.IDLE;
            return;
        }

        this.recording = full;
        this.segments = full.getSegments();
        this.keyframes = full.getKeyframes();
        beginWalkingToStart(client);
    }

    private void beginWalkingToStart(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || recording == null) return;

        double dx = recording.getStartX() - player.getX();
        double dz = recording.getStartZ() - player.getZ();
        if (dx * dx + dz * dz <= ARRIVAL_SQ) {
            beginPlayback(client);
            return;
        }

        state = State.MOVING_TO_START;
        clearOutputs();
        playForward = 1.0f;
        MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.walking");
    }

    private void beginPlayback(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || segments.isEmpty()) return;

        state = State.PLAYING;
        segmentIndex = 0;
        segmentTick = 0;
        totalTick = 0;
        sprintOffTicks = 0;
        keyframeIndex = 0;

        loadSegment(0);
        player.setYRot(recording.getStartYaw());
        player.setYHeadRot(recording.getStartYaw());
        player.setXRot(recording.getStartPitch());
        MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.started");
    }

    public void stop() {
        State previous = state;
        state = State.IDLE;
        clearOutputs();
        sprintOffTicks = 0;
        releaseRecording();

        Minecraft client = Minecraft.getInstance();
        if (previous != State.IDLE && client.player != null) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.stopped");
        }
    }

    /**
     * Drop the loaded recording. A long recording is several megabytes of segments; without this
     * it stayed reachable until the next {@code start()} replaced it.
     */
    private void releaseRecording() {
        recording = null;
        segments = null;
        keyframes = null;
        currentSegment = null;
    }

    private void clearOutputs() {
        playForward = 0;
        playSideways = 0;
        playJump = false;
        playSneak = false;
        playSprint = false;
        playLeftClick = false;
        playRightClick = false;
    }

    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.LOADING || state == State.COMPLETED) return;

        LocalPlayer player = client.player;
        if (player == null || recording == null || segments == null) {
            state = State.IDLE;
            releaseRecording();
            return;
        }

        if (state == State.MOVING_TO_START) {
            tickWalkingToStart(client, player);
            return;
        }

        checkDrift(client, player);

        // Sprint release delay tracking; the key itself is handled by ClientEventHandler.
        if (playSprint) {
            sprintOffTicks = 0;
        } else if (sprintOffTicks < SPRINT_RELEASE_TICKS) {
            sprintOffTicks++;
        }

        segmentTick++;
        totalTick++;

        if (segmentTick >= currentSegment.duration) {
            segmentIndex++;
            if (segmentIndex >= segments.size()) {
                onRecordingEnd(client);
                return;
            }
            loadSegment(segmentIndex);
        }
    }

    private void tickWalkingToStart(Minecraft client, LocalPlayer player) {
        double dx = recording.getStartX() - player.getX();
        double dz = recording.getStartZ() - player.getZ();
        if (dx * dx + dz * dz <= ARRIVAL_SQ) {
            beginPlayback(client);
            return;
        }

        clearOutputs();
        playYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        playPitch = 0;
        playForward = 1.0f;
    }

    private void onRecordingEnd(Minecraft client) {
        currentPlay++;
        if (playCount == 0 || currentPlay < playCount) {
            beginWalkingToStart(client);
            return;
        }
        state = State.COMPLETED;
        clearOutputs();
        releaseRecording();
        MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.completed");
    }

    /**
     * Compare the player's position against the next due keyframe.
     *
     * <p>Simulated input cannot reproduce a path exactly — collisions, latency and server-side
     * movement resolution all nudge it — so some drift is expected and is reported rather than
     * papered over. Hard correction rewrites the client's position, which desyncs from the
     * server's authoritative one and reads as flying to anti-cheat, so it is opt-in and off by
     * default; see {@code Configs.Settings.PLAYBACK_POSITION_CORRECTION}.
     */
    private void checkDrift(Minecraft client, LocalPlayer player) {
        if (keyframes == null || keyframeIndex >= keyframes.size()) return;

        PositionKeyframe kf = keyframes.get(keyframeIndex);
        if (totalTick < kf.tick) return;
        keyframeIndex++;

        double dx = player.getX() - kf.x;
        double dy = player.getY() - kf.y;
        double dz = player.getZ() - kf.z;
        double driftSq = dx * dx + dy * dy + dz * dz;

        if (driftSq > DRIFT_CORRECT_SQ
                && Configs.Settings.PLAYBACK_POSITION_CORRECTION.getBooleanValue()) {
            player.setPos(kf.x, kf.y, kf.z);
            return;
        }

        if (driftSq > DRIFT_WARN_SQ && !driftWarned) {
            driftWarned = true;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.drift");
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

    /** Apply the recorded look direction. Called from the player-tick mixin. */
    public void applyYaw(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        if (state == State.MOVING_TO_START) {
            if (recording != null) {
                player.setYRot(Mth.wrapDegrees(playYaw));
                player.setYHeadRot(Mth.wrapDegrees(playYaw));
            }
            return;
        }
        if (state != State.PLAYING || currentSegment == null) return;

        player.setYRot(Mth.wrapDegrees(playYaw));
        player.setYHeadRot(Mth.wrapDegrees(playYaw));
        player.setXRot(playPitch);
    }
}
