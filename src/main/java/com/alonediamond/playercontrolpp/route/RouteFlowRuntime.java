package com.alonediamond.playercontrolpp.route;

import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.*;

public class RouteFlowRuntime {
    private static final RouteFlowRuntime INSTANCE = new RouteFlowRuntime();

    private final Map<String, RouteExecutor> executors = new LinkedHashMap<>();
    private boolean forwardActive = false;

    private RouteFlowRuntime() {}

    public static RouteFlowRuntime getInstance() { return INSTANCE; }

    public boolean isForwardActive() { return forwardActive; }

    public boolean isSprintRequested() {
        for (RouteExecutor executor : executors.values()) {
            if (executor.isActive() && executor.getRoute().isSprintEnabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start a route. Returns false if the route has no dimension set and player is not in a world.
     */
    public boolean startRoute(Route route) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return false;

        // Auto-set dimension on first start
        if (route.getDimensionId().isEmpty()) {
            route.setDimension(client.level.dimension());
        }

        stopAllRoutes();

        RouteExecutor executor = new RouteExecutor(route);
        executor.start();
        executors.put(route.getId(), executor);
        forwardActive = true;

        MessageUtil.sendActionBar(client, "playercontrolpp.message.route.started");
        return true;
    }

    public void stopRoute(Route route) {
        RouteExecutor executor = executors.remove(route.getId());
        if (executor != null) {
            executor.stop();
            updateForwardState();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.route.stopped");
            }
        }
    }

    public void stopAllRoutes() {
        for (RouteExecutor executor : executors.values()) {
            executor.stop();
        }
        executors.clear();
        forwardActive = false;
    }

    public void toggleRoute(Route route) {
        if (executors.containsKey(route.getId())) {
            stopRoute(route);
        } else {
            startRoute(route);
        }
    }

    /**
     * Called from direction-checking code to provide the currently desired forward input.
     */
    public float getForwardInput() {
        return forwardActive ? 1.0F : 0.0F;
    }

    public void onClientTick(Minecraft client) {
        if (executors.isEmpty()) return;

        LocalPlayer player = client.player;
        if (player == null) {
            stopAllRoutes();
            return;
        }

        // Check death
        if (player.isDeadOrDying()) {
            for (RouteExecutor executor : executors.values()) {
                executor.stop();
            }
            executors.clear();
            forwardActive = false;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.route.death");
            return;
        }

        // Tick all executors
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RouteExecutor> entry : executors.entrySet()) {
            RouteExecutor executor = entry.getValue();
            executor.tick(client);

            // Per-traversal layer increment: fires at each endpoint arrival,
            // not just at route completion. For infinite loops (loopCount=0),
            // this triggers continuously on every pass through the waypoints.
            if (executor.getRoute().isLayerControlEnabled()
                    && executor.consumeLayerIncrementPending()) {
                LitematicaIntegration.getInstance().incrementLayer(
                        executor.getRoute().getLayerIncrement());
            }

            switch (executor.getState()) {
                case COMPLETED:
                    MessageUtil.sendActionBar(client, "playercontrolpp.message.route.completed");
                    toRemove.add(entry.getKey());
                    break;
                case FAILED:
                    MessageUtil.sendActionBar(client, "playercontrolpp.message.route.failed");
                    toRemove.add(entry.getKey());
                    break;
                case IDLE:
                case MOVING:
                case STUCK_JUMP:
                    break;
            }
        }

        for (String key : toRemove) {
            RouteExecutor executor = executors.remove(key);
            if (executor != null) executor.stop();
        }
        updateForwardState();

        // Handle jump requests
        for (RouteExecutor executor : executors.values()) {
            if (executor.needsJump() && player != null) {
                player.jumpFromGround();
                executor.clearJump();
            }
        }
    }

    private void updateForwardState() {
        forwardActive = false;
        for (RouteExecutor executor : executors.values()) {
            if (executor.isActive()) {
                forwardActive = true;
                break;
            }
        }
    }

    /**
     * Called when world changes (dimension switch, disconnect, etc.)
     */
    public void onWorldChange() {
        if (!executors.isEmpty()) {
            Minecraft client = Minecraft.getInstance();
            stopAllRoutes();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.route.world_change");
            }
        }
    }
}
