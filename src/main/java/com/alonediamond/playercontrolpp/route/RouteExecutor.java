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

    // Turn rate in degrees per tick, tiered by how far off course we are: snap hard at a waypoint,
    // ease in on small corrections so the walk does not weave.
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
    private boolean layerIncrementPending; // set on boundary arrival for per-traversal layer change
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

        // Find closest waypoint to start from (XZ only, ignore Y)
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

        // XZ-only distance (ignore Y to avoid vertical mismatch issues)
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
     * Turn to face {@code target} instantly.
     *
     * <p>{@code yRotO} has to be set too. The camera interpolates between {@code yRotO} and
     * {@code yRot} across the frames of a tick, so setting only {@code yRot} turns a snap into a
     * 50 ms smear — visible as a smooth sweep at every waypoint rather than the intended cut.
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

        // Advance the waypoint index in current direction
        currentWPIndex += direction;

        // Reverse direction at endpoints of the waypoint list.
        // This creates a back-and-forth (ping-pong) traversal pattern:
        // start -> ... -> end -> ... -> start -> ...
        if (currentWPIndex >= nodes.size() - 1) {
            direction = -1;
            currentWPIndex = nodes.size() - 1;
        } else if (currentWPIndex <= 0) {
            direction = 1;
            currentWPIndex = 0;
        }

        // Fires once per full traversal (arriving at either endpoint).
        // For loopCount=0 (infinite), this fires every time the player
        // reaches an endpoint, enabling continuous per-pass layer changes.
        if (currentWPIndex == 0 || currentWPIndex == nodes.size() - 1) {
            layerIncrementPending = true;
        }

        int nextIdx = currentWPIndex + direction;
        currentTarget = nodes.get(nextIdx);

        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;

        // Snap yaw to face next target
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            snapYawToTarget(client, currentTarget);
        }
    }

    public boolean needsJump() { return jumpRequested; }
    public void clearJump() { jumpRequested = false; }

    /** Atomic get-and-clear: returns true exactly once per pending increment,
     *  preventing duplicate layer changes across multiple tick iterations. */
    public boolean consumeLayerIncrementPending() {
        if (layerIncrementPending) {
            layerIncrementPending = false;
            return true;
        }
        return false;
    }
}
