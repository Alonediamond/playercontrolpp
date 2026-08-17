package com.alonediamond.playercontrolpp.route;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class RouteExecutor {

    public enum State {
        IDLE,
        MOVING,
        STUCK_JUMP,
        FAILED,
        COMPLETED
    }

    private static final double STUCK_THRESHOLD_SQ = 0.01;
    private static final int STUCK_TICKS = 60;
    private static final int STUCK_JUMP_TICKS = 100;
    private static final double YAW_DEAD_ZONE = 2.0;

    // 每 tick 的转向速度（度），按偏离程度分档：到达导航点时直接对准，小幅修正则缓一点，
    // 免得走出蛇形。
    private static final double YAW_SPEED_SMALL = 15.0;
    private static final double YAW_SPEED_MEDIUM = 18.0;
    private static final double YAW_SPEED_LARGE = 25.0;
    private static final double YAW_MEDIUM_THRESHOLD = 15.0;
    private static final double YAW_LARGE_THRESHOLD = 45.0;

    private final Route route;
    private State state = State.IDLE;
    private RouteNode currentTarget;
    private int currentWPIndex;
    private int direction;
    private int completedSegments;
    private int totalSegments;
    private int stuckTicks;
    private int postJumpTicks;
    private boolean jumpRequested;
    private boolean layerIncrementPending; // 到达端点时置位，用于每趟换层
    private Vec3 lastPosition = Vec3.ZERO;

    public RouteExecutor(Route route) {
        this.route = route;
    }

    public Route getRoute() { return route; }
    public State getState() { return state; }
    public int getCompletedSegments() { return completedSegments; }
    public int getTotalSegments() { return totalSegments; }
    public RouteNode getCurrentTarget() { return currentTarget; }

    public boolean isActive() {
        return state == State.MOVING || state == State.STUCK_JUMP;
    }

    public void start() {
        List<RouteNode> nodes = route.getNodes();
        if (nodes.size() < 2) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        state = State.MOVING;
        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;
        lastPosition = new Vec3(player.getX(), player.getY(), player.getZ());

        // 找离起点最近的导航点（只看 XZ，忽略 Y）
        double bestDist = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode node = nodes.get(i);
            double dx = node.x - player.getX();
            double dz = node.z - player.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }

        // 定初始方向与目标
        currentWPIndex = bestIdx;
        if (bestIdx < nodes.size() - 1) {
            direction = 1;
        } else {
            direction = -1;
        }

        // 按选定方向前往下一个导航点
        int nextIdx = currentWPIndex + direction;
        if (nextIdx < 0 || nextIdx >= nodes.size()) {
            // 玩家就在唯一可用的导航点上；强制指定方向
            direction = -direction;
            nextIdx = currentWPIndex + direction;
        }
        currentTarget = nodes.get(nextIdx);

        totalSegments = route.getTotalSegments();
        completedSegments = 0;

        // 立刻把 yaw 对准第一个目标
        snapYawToTarget(client, currentTarget);
    }

    public void stop() {
        state = State.IDLE;
        jumpRequested = false;
    }

    public void tick(Minecraft client) {
        if (!isActive()) return;

        LocalPlayer player = client.player;
        if (player == null || player.isDeadOrDying()) {
            state = State.IDLE;
            return;
        }

        String currentDim = client.level.dimension().identifier().toString();
        if (!route.getDimensionId().isEmpty() && !route.getDimensionId().equals(currentDim)) {
            state = State.FAILED;
            return;
        }

        Vec3 currentPos = new Vec3(player.getX(), player.getY(), player.getZ());

        // 只算 XZ 距离（忽略 Y，避免垂直落差造成误判）
        double dx = currentTarget.x - currentPos.x;
        double dz = currentTarget.z - currentPos.z;
        double distSq = dx * dx + dz * dz;
        double arrivalSq = route.getArrivalRadius() * route.getArrivalRadius();

        if (distSq <= arrivalSq) {
            onArrival();
            if (!isActive()) return;
        }

        // Stuck detection
        double movedSq = currentPos.distanceToSqr(lastPosition);
        if (movedSq < STUCK_THRESHOLD_SQ) {
            stuckTicks++;
            if (state == State.MOVING) {
                if (stuckTicks >= STUCK_TICKS) {
                    state = State.STUCK_JUMP;
                    jumpRequested = true;
                    postJumpTicks = 0;
                    stuckTicks = 0;
                }
            } else if (state == State.STUCK_JUMP) {
                postJumpTicks++;
                if (postJumpTicks >= STUCK_JUMP_TICKS) {
                    state = State.FAILED;
                    return;
                }
            }
        } else {
            if (state == State.STUCK_JUMP) {
                state = State.MOVING;
                postJumpTicks = 0;
            }
            stuckTicks = 0;
            jumpRequested = false;
        }

        lastPosition = currentPos;

        if (state == State.MOVING || state == State.STUCK_JUMP) {
            adjustYaw(client, currentTarget);
        }
    }

    /**
     * 瞬间转向面对 {@code target}。
     *
     * <p>{@code yRotO} 必须一起设。相机会在一 tick 的若干帧之间对 {@code yRotO} 和 {@code yRot}
     * 插值，只设 {@code yRot} 会把「瞬转」变成 50 ms 的拖影——表现是每到一个导航点就平滑扫一下，
     * 而不是想要的硬切。
     */
    private void snapYawToTarget(Minecraft client, RouteNode target) {
        LocalPlayer player = client.player;
        if (player == null) return;

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yRotO = yaw;
        player.yHeadRotO = yaw;
    }

    private void adjustYaw(Minecraft client, RouteNode target) {
        LocalPlayer player = client.player;
        if (player == null) return;

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float currentYaw = Mth.wrapDegrees(player.getYRot());
        float diff = Mth.wrapDegrees(desiredYaw - currentYaw);

        if (Math.abs(diff) < YAW_DEAD_ZONE) return;

        double speed = YAW_SPEED_SMALL;
        if (Math.abs(diff) > YAW_LARGE_THRESHOLD) {
            speed = YAW_SPEED_LARGE;
        } else if (Math.abs(diff) > YAW_MEDIUM_THRESHOLD) {
            speed = YAW_SPEED_MEDIUM;
        }

        float correction = (float) Math.copySign(
                Math.min(Math.abs(diff), (float) speed), diff);

        float newYaw = Mth.wrapDegrees(currentYaw + correction);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
    }

    private void onArrival() {
        completedSegments++;
        if (totalSegments > 0 && completedSegments >= totalSegments) {
            state = State.COMPLETED;
            return;
        }

        List<RouteNode> nodes = route.getNodes();

        // 按当前方向推进导航点下标
        currentWPIndex += direction;

        // 走到导航点列表的端点就反向，形成往返（ping-pong）遍历：
        // 起点 -> …… -> 终点 -> …… -> 起点 -> ……
        if (currentWPIndex >= nodes.size() - 1) {
            direction = -1;
            currentWPIndex = nodes.size() - 1;
        } else if (currentWPIndex <= 0) {
            direction = 1;
            currentWPIndex = 0;
        }

        // 每完成一次完整遍历（到达任一端点）触发一次。
        // loopCount=0（无限循环）时，每次到达端点都会触发，从而实现每趟自动换层。
        if (currentWPIndex == 0 || currentWPIndex == nodes.size() - 1) {
            layerIncrementPending = true;
        }

        int nextIdx = currentWPIndex + direction;
        currentTarget = nodes.get(nextIdx);

        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;

        // 把 yaw 对准下一个目标
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            snapYawToTarget(client, currentTarget);
        }
    }

    public boolean needsJump() { return jumpRequested; }
    public void clearJump() { jumpRequested = false; }

    /** 取值并清零的原子操作：每个待处理的增量只返回 true 一次，
     *  避免同一次换层在多个 tick 里被重复执行。 */
    public boolean consumeLayerIncrementPending() {
        if (layerIncrementPending) {
            layerIncrementPending = false;
            return true;
        }
        return false;
    }
}
