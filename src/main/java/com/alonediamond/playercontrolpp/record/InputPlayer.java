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

    /** 走到路点的判定半径平方（1 格）。 */
    private static final double WAYPOINT_ARRIVAL_SQ = 1.0;
    /** 路点的垂直判定半径（格）。 */
    private static final double WAYPOINT_ARRIVAL_Y = 2.0;
    /** 多少 tick 没靠近路点就算卡住。 */
    private static final int STUCK_TICKS = 40;
    /** 卡住时强制跳多少 tick（翻一格台阶、跳上坎）。 */
    private static final int JUMP_BURST_TICKS = 10;
    /** 跳几次还是没动就放弃回程。 */
    private static final int MAX_STUCK_BURSTS = 4;
    /** 回程总超时（60 秒）。 */
    private static final int RETURN_TIMEOUT_TICKS = 1200;

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

    // 离散动作事件（丢、换手、快捷栏、发言、命令），按 tick 升序，回放时用前进指针触发
    private List<RecordedAction> actions;
    private int actionIndex;

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

    // 回到起点（循环模式）：沿关键帧从最后一个倒着走回录制起点。
    /** 下一个要走到的关键帧下标；{@code -1} 表示目标就是录制起点本身。 */
    private int returnIndex = -1;
    /** 上一 tick 选中的路点，换路点时重置卡住计数。 */
    private int returnTarget = -1;
    /** 到当前路点最近一次的距离平方，用来判断有没有在靠近。 */
    private double returnBestDistSq = Double.MAX_VALUE;
    /** 连续没靠近路点的 tick 数。 */
    private int stuckTicks;
    /** 卡住时强制跳的剩余 tick 数。 */
    private int jumpTicks;
    /** 已经跳了几次还是没动。 */
    private int stuckBursts;
    /** 回到起点已经花掉的 tick 数。 */
    private int returnTicks;
    /** 位置修正因为联机被跳过时只提示一次。 */
    private boolean correctionSkippedWarned;

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

    /**
     * 开始回放 {@code indexRec} 描述的那条录制。
     *
     * <p>主体数据在后台线程读取，读完在回调里才真正开始回放，所以点播放不会卡帧。
     *
     * @return 真的开始加载了才返回 {@code true}；已在回放、或正在录制时返回 {@code false}
     */
    public boolean start(RecordingFile indexRec, int playCount) {
        if (indexRec == null || isBusy()) return false;

        Minecraft client = Minecraft.getInstance();
        // 录制和回放同时跑会互相污染：回放按下的键会被录进去。
        if (RecordingManager.getInstance().getRecorder().isRecording()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.recording_active");
            return false;
        }

        this.playCount = playCount;
        this.currentPlay = 0;
        this.sprintOffTicks = 0;
        this.keyframeIndex = 0;
        this.driftWarned = false;
        this.state = State.LOADING;

        RecordingManager.getInstance().loadRecordingFileAsync(indexRec.getId(), this::onLoaded);
        return true;
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
        if (inWrongDimension(client, full)) {
            state = State.IDLE;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.playback.wrong_dimension");
            return;
        }

        this.recording = full;
        this.segments = full.getSegments();
        this.keyframes = full.getKeyframes();
        this.actions = full.getActions();
        beginWalkingToStart(client);
    }

    private void beginWalkingToStart(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || recording == null) return;

        if (atStart(player)) {
            beginPlayback(client);
            return;
        }

        state = State.MOVING_TO_START;
        returnIndex = keyframes == null ? -1 : keyframes.size() - 1;
        returnTarget = -2;
        returnBestDistSq = Double.MAX_VALUE;
        returnTicks = 0;
        stuckTicks = 0;
        jumpTicks = 0;
        stuckBursts = 0;
        clearOutputs();
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
        actionIndex = 0;
        correctionSkippedWarned = false;
        returnIndex = -1;
        returnTicks = 0;
        stuckTicks = 0;
        jumpTicks = 0;
        stuckBursts = 0;

        loadSegment(0);
        // 恢复录制时选中的快捷栏槽位：录制里的丢/吃/放都假定手持那一格。
        com.alonediamond.playercontrolpp.compat.InventoryCompat.setSelectedSlot(
                player.getInventory(), Math.max(0, Math.min(8, recording.getStartSlot())));
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
        actions = null;
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
            if (player.isDeadOrDying() || inWrongDimension(client, recording)) {
                abort(client, "playercontrolpp.message.playback.interrupted");
                return;
            }
            tickWalkingToStart(client, player);
            return;
        }

        // 死亡或换维度后位置已经不是录制时的位置，继续放只会一路跑偏。
        if (player.isDeadOrDying() || inWrongDimension(client, recording)) {
            abort(client, "playercontrolpp.message.playback.interrupted");
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

        // 本 tick 该触发的离散动作（tick 相等即触发），赶在录制结束判断之前——
        // 最后一个 tick 上记的动作也要放出去。
        fireDueActions(client, totalTick);

        if (segmentTick >= currentSegment.duration) {
            segmentIndex++;
            if (segmentIndex >= segments.size()) {
                onRecordingEnd(client);
                return;
            }
            loadSegment(segmentIndex);
        }
    }

    /**
     * 走回录制起点。循环模式每放完一遍就走一次。
     *
     * <p>直线冲向起点在联机里几乎必然失败：撞墙、掉坑、绕不过拐角。所以这里沿录制时留下的
     * 位置关键帧<b>倒着</b>走——关键帧每 20 tick 一个，等于一条粗略的回程路线。走完关键帧
     * 再走起点本身。
     *
     * <p>卡住（40 tick 没有靠近 0.25 格）就跳一下翻台阶；跳几次还是不动、或者总时长超过
     * {@link #RETURN_TIMEOUT_TICKS}，就放弃回程并停下，而不是一直按着 W 顶着墙。
     */
    private void tickWalkingToStart(Minecraft client, LocalPlayer player) {
        if (atStart(player)) {
            beginPlayback(client);
            return;
        }
        if (++returnTicks > RETURN_TIMEOUT_TICKS) {
            abort(client, "playercontrolpp.message.playback.return_failed");
            return;
        }

        // 目标：关键帧从最后一个倒着走回去，走完就用录制起点。
        int target = -1;
        double tx = recording.getStartX();
        double ty = recording.getStartY();
        double tz = recording.getStartZ();
        while (returnIndex >= 0) {
            PositionKeyframe kf = keyframes.get(returnIndex);
            if (reached(player, kf.x, kf.y, kf.z)) {
                returnIndex--;
                continue;
            }
            target = returnIndex;
            tx = kf.x;
            ty = kf.y;
            tz = kf.z;
            break;
        }

        if (target != returnTarget) {
            returnTarget = target;
            returnBestDistSq = Double.MAX_VALUE;
            stuckTicks = 0;
            stuckBursts = 0;
        }

        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < returnBestDistSq - 0.0625) {
            returnBestDistSq = distSq;
            stuckTicks = 0;
            stuckBursts = 0;
        } else if (++stuckTicks >= STUCK_TICKS) {
            stuckTicks = 0;
            jumpTicks = JUMP_BURST_TICKS;
            if (++stuckBursts >= MAX_STUCK_BURSTS) {
                abort(client, "playercontrolpp.message.playback.return_failed");
                return;
            }
        }

        clearOutputs();
        playYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        playPitch = 0;
        playForward = 1.0f;
        playSprint = true;
        // 目标比脚下高就跳；卡住时也跳一段。
        playJump = jumpTicks > 0 || ty > player.getY() + 1.0;
        if (jumpTicks > 0) jumpTicks--;
    }

    /** @return 玩家是否已经在录制起点上（只看水平距离）。 */
    private boolean atStart(LocalPlayer player) {
        double dx = recording.getStartX() - player.getX();
        double dz = recording.getStartZ() - player.getZ();
        return dx * dx + dz * dz <= ARRIVAL_SQ;
    }

    /** @return 玩家是否已经站到路点 {@code (x, y, z)} 上。 */
    private static boolean reached(LocalPlayer player, double x, double y, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        return dx * dx + dz * dz <= WAYPOINT_ARRIVAL_SQ
                && Math.abs(y - player.getY()) <= WAYPOINT_ARRIVAL_Y;
    }

    /** @return 录制维度与当前维度不一致；老录制没有维度信息时一律视为一致。 */
    private static boolean inWrongDimension(Minecraft client, RecordingFile rec) {
        String dim = rec.getDimension();
        if (dim == null || dim.isEmpty() || client.level == null) return false;
        return !dim.equals(client.level.dimension().identifier().toString());
    }

    /** 中断回放：清状态、清输出、放掉录制，并显示 {@code messageKey}。 */
    private void abort(Minecraft client, String messageKey) {
        state = State.IDLE;
        clearOutputs();
        sprintOffTicks = 0;
        releaseRecording();
        MessageUtil.sendActionBar(client, messageKey);
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
     * 因此它是可选项、默认关闭，且只在单人世界里生效，见
     * {@code Configs.Settings.PLAYBACK_POSITION_CORRECTION}。
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
            // 联机时服务端才是位置的权威：把客户端拉回去只会被服务端拉回来，还可能被反作弊
            // 当成飞行。所以只在单人（含局域网主机）世界里真的改坐标。
            if (client.hasSingleplayerServer()) {
                player.setPos(kf.x, kf.y, kf.z);
                return;
            }
            if (!correctionSkippedWarned) {
                correctionSkippedWarned = true;
                MessageUtil.sendActionBar(client,
                        "playercontrolpp.message.playback.correction_skipped");
            }
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
        // 回放同样受「录制功能」开关约束：关掉的类别不驱动对应的键，
        // 这样即使录制里带了那段数据（旧文件、或录制时开着），回放也不会执行。
        boolean move = Configs.Settings.RECORD_MOVEMENT.getBooleanValue();
        boolean look = Configs.Settings.RECORD_LOOK.getBooleanValue();
        playForward = move ? currentSegment.forward : 0f;
        playSideways = move ? currentSegment.sideways : 0f;
        playJump = move && currentSegment.jump;
        playSneak = move && currentSegment.sneak;
        playSprint = move && currentSegment.sprint;
        playLeftClick = Configs.Settings.RECORD_ATTACK.getBooleanValue()
                && currentSegment.attack;
        playRightClick = Configs.Settings.RECORD_USE.getBooleanValue()
                && currentSegment.use;
        playYaw = look ? currentSegment.yaw : 0f;
        playPitch = look ? currentSegment.pitch : 0f;
    }

    /**
     * 触发 {@code tick} 及之前还没放过的全部动作事件。列表按 tick 升序，指针只前进。
     */
    private void fireDueActions(Minecraft client, int tick) {
        if (actions == null) return;
        while (actionIndex < actions.size()) {
            RecordedAction action = actions.get(actionIndex);
            if (action.tick > tick) break;
            actionIndex++;
            executeAction(client, action);
        }
    }

    /** 重放一条离散动作。与录制时一样走 vanilla 入口，服务端看到的是同一批包。 */
    private void executeAction(Minecraft client, RecordedAction action) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) return;

        switch (action.type == null ? "" : action.type) {
            case RecordedAction.TYPE_DROP:
            case RecordedAction.TYPE_DROP_ALL: {
                if (!Configs.Settings.RECORD_DROP.getBooleanValue()) return;
                boolean dropAll = action.type.equals(RecordedAction.TYPE_DROP_ALL);
                com.alonediamond.playercontrolpp.compat.DropCompat.dropSelected(player, dropAll);
                break;
            }
            case RecordedAction.TYPE_HOTBAR: {
                if (!Configs.Settings.RECORD_HOTBAR.getBooleanValue()) return;
                int slot = Math.max(0, Math.min(8, action.slot));
                com.alonediamond.playercontrolpp.compat.InventoryCompat.setSelectedSlot(
                        player.getInventory(), slot);
                // 服务端的选中格由 MultiPlayerGameMode.tick() 的 ensureHasSentCarriedItem
                // 自动补发，与玩家手按数字键路径一致。
                break;
            }
            case RecordedAction.TYPE_SWAP_HANDS: {
                if (!Configs.Settings.RECORD_HOTBAR.getBooleanValue()) return;
                com.alonediamond.playercontrolpp.compat.SlotActionCompat.swapWithOffhand(client);
                break;
            }
            case RecordedAction.TYPE_CHAT: {
                if (!Configs.Settings.RECORD_CHAT.getBooleanValue()) return;
                if (player.connection != null) player.connection.sendChat(action.text);
                break;
            }
            case RecordedAction.TYPE_COMMAND: {
                if (!Configs.Settings.RECORD_CHAT.getBooleanValue()) return;
                if (player.connection != null && action.text != null && action.text.length() > 1) {
                    player.connection.sendCommand(action.text.substring(1));
                }
                break;
            }
            default:
                // 未知类型：跳过，别让一条坏事件掀掉整场回放。
        }
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

        // 视角录制关掉时不覆盖玩家朝向。
        if (!Configs.Settings.RECORD_LOOK.getBooleanValue()) return;

        player.setYRot(Mth.wrapDegrees(playYaw));
        player.setYHeadRot(Mth.wrapDegrees(playYaw));
        player.setXRot(playPitch);
    }
}
