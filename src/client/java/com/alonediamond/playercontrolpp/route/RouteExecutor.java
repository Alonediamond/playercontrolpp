package com.alonediamond.playercontrolpp.route;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

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
    private static final double YAW_CORRECTION_SPEED = 15.0;
    private static final double YAW_DEAD_ZONE = 2.0;

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
    private Vec3d lastPosition = Vec3d.ZERO;

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

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        state = State.MOVING;
        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;
        lastPosition = player.getPos();

        // Find closest waypoint to start from
        double bestDist = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode node = nodes.get(i);
            double d = player.squaredDistanceTo(node.x, node.y, node.z);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }

        // Determine initial direction and target
        currentWPIndex = bestIdx;
        if (bestIdx < nodes.size() - 1) {
            direction = 1;
        } else {
            direction = -1;
        }

        // Move to next waypoint in chosen direction
        int nextIdx = currentWPIndex + direction;
        if (nextIdx < 0 || nextIdx >= nodes.size()) {
            // Player is at the only valid waypoint; force direction
            direction = -direction;
            nextIdx = currentWPIndex + direction;
        }
        currentTarget = nodes.get(nextIdx);

        totalSegments = route.getTotalSegments();
        completedSegments = 0;

        // Snap yaw to face the first target immediately
        snapYawToTarget(client, currentTarget);
    }

    public void stop() {
        state = State.IDLE;
        jumpRequested = false;
    }

    public void tick(MinecraftClient client) {
        if (!isActive()) return;

        ClientPlayerEntity player = client.player;
        if (player == null || player.isDead()) {
            state = State.IDLE;
            return;
        }

        String currentDim = player.getWorld().getRegistryKey().getValue().toString();
        if (!route.getDimensionId().isEmpty() && !route.getDimensionId().equals(currentDim)) {
            state = State.FAILED;
            return;
        }

        Vec3d currentPos = player.getPos();

        double distSq = currentTarget.squaredDistanceTo(currentPos.x, currentPos.y, currentPos.z);
        double arrivalSq = route.getArrivalRadius() * route.getArrivalRadius();

        if (distSq <= arrivalSq) {
            onArrival();
            if (!isActive()) return;
        }

        // Stuck detection
        double movedSq = currentPos.squaredDistanceTo(lastPosition);
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

    private void snapYawToTarget(MinecraftClient client, RouteNode target) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        yaw = MathHelper.wrapDegrees(yaw);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
    }

    private void adjustYaw(MinecraftClient client, RouteNode target) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float currentYaw = MathHelper.wrapDegrees(player.getYaw());
        float diff = MathHelper.wrapDegrees(desiredYaw - currentYaw);

        if (Math.abs(diff) < YAW_DEAD_ZONE) return;

        // Smooth correction: faster for large angles, slower for small
        double speed = YAW_CORRECTION_SPEED;
        if (Math.abs(diff) > 45.0) {
            speed = 25.0; // very fast for large deviations
        } else if (Math.abs(diff) > 15.0) {
            speed = 18.0;
        }

        float correction = (float) Math.copySign(
                Math.min(Math.abs(diff), (float) speed), diff);

        float newYaw = MathHelper.wrapDegrees(currentYaw + correction);
        player.setYaw(newYaw);
        player.setHeadYaw(newYaw);
    }

    private void onArrival() {
        completedSegments++;
        if (totalSegments > 0 && completedSegments >= totalSegments) {
            state = State.COMPLETED;
            return;
        }

        List<RouteNode> nodes = route.getNodes();
        currentWPIndex += direction;

        // Reverse at boundaries
        if (currentWPIndex >= nodes.size() - 1) {
            direction = -1;
            currentWPIndex = nodes.size() - 1;
        } else if (currentWPIndex <= 0) {
            direction = 1;
            currentWPIndex = 0;
        }

        int nextIdx = currentWPIndex + direction;
        currentTarget = nodes.get(nextIdx);

        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;
    }

    public boolean needsJump() { return jumpRequested; }

    public void clearJump() { jumpRequested = false; }
}
