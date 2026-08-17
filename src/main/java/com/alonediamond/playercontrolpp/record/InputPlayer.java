package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 按 RLE 段列表回放录制的输入：每段带一个输入状态和持续 tick 数，保持那么多 tick 再加载下一段。
 *
 * <pre>
 * IDLE -&gt; LOADING -&gt; MOVING_TO_START -&gt; PLAYING -&gt; COMPLETED（或循环回去）
 * </pre>
 *
 * <p>本类不按任何键。它通过 getter 公布「想要的输入」，由 {@code ClientEventHandler} 经
 * {@code SimulatedInput} 落成按住的键——这正是让回放符合「移动只能是模拟输入」这条约定的做法。
 */
public class InputPlayer {

    public enum State { IDLE, LOADING, MOVING_TO_START, PLAYING, COMPLETED }

    /** 走向起点时的到达阈值平方（0.5 格）。 */
    private static final double ARRIVAL_SQ = 0.25;
    /**
     * 可选的硬修正触发阈值的平方（2 格）。
     *
     * <p>旧值是 0.04，也就是 0.2 格——普通的服务端移动精度几乎每次都会超过它，
     * 于是修正差不多每个关键帧都触发：一秒一次传送。改成 2 格后只有真的跑偏了才介入。
     */
    private static final double DRIFT_CORRECT_SQ = 4.0;
    /** 超过这个偏差就提示玩家回放已偏离，平方值（4 格）。 */
    private static final double DRIFT_WARN_SQ = 16.0;
    /** 疾跑关闭后疾跑键额外保持松开的 tick 数，让服务端能识别到。 */
    private static final int SPRINT_RELEASE_TICKS = 3;

    private RecordingFile recording;
    private State state = State.IDLE;
    private int playCount;       // 0 = 无限循环，N = 重复 N 次
    private int currentPlay;     // 已经放了几遍

    // 基于段的回放（运行时做 RLE 解压）
    private List<RecordedSegment> segments;
    private int segmentIndex;    // 当前在放第几段
    private int segmentTick;     // 在这一段里已过多少 tick
    private int totalTick;       // 绝对 tick 计数，用于对齐关键帧

    private RecordedSegment currentSegment;

    // 位置关键帧，用来检测偏差
    private List<PositionKeyframe> keyframes;
    private int keyframeIndex;   // 下一个要检查的关键帧
    private boolean driftWarned; // 每次回放只提示一次，不是每个关键帧一次

    // 每 tick 由 ClientEventHandler 读取的输出值
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

    /** @return 回放是否正在实际驱动玩家。 */
    public boolean isPlaying() { return state == State.PLAYING || state == State.MOVING_TO_START; }

    /** @return 回放是否正在跑或即将跑，含异步加载阶段。 */
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
     * 开始回放 {@code indexRec} 描述的那条录制。
     *
     * <p>主体数据在后台线程读取，读完在回调里才真正开始回放，所以点播放不会卡帧。
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
        // 读取期间玩家可能已经按了停止，或者离开了世界。
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
     * 丢掉已加载的录制。长录制是好几兆的段数据；不丢的话它会一直可达到下一次 {@code start()} 才被替换。
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

        // 疾跑释放延迟的记账；键本身由 ClientEventHandler 处理。
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
     * 把玩家位置和下一个到期的关键帧比对。
     *
     * <p>模拟输入没法精确复现路径——碰撞、延迟、服务端移动判定都会把它推偏——所以偏差是预期之内的，
     * 只报告而不掩盖。硬修正会改写客户端坐标，与服务端权威位置不一致，在反作弊眼里像飞行，
     * 因此它是可选项且默认关闭，见 {@code Configs.Settings.PLAYBACK_POSITION_CORRECTION}。
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

    /** 应用录制里的视角方向。由玩家 tick 的 Mixin 调用。 */
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
