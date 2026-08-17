package com.alonediamond.playercontrolpp.route;

import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.*;

/**
 * 运行活动中的路径执行器，并公布它们想要的前进/疾跑输入。键本身由 {@code ClientEventHandler} 按下。
 */
public class RouteFlowRuntime implements ClientFeature {
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
     * 启动一条路径。路径没设维度且玩家不在世界里时返回 false。
     */
    public boolean startRoute(Route route) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return false;

        // 首次启动时自动写入维度
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

    @Override
    public boolean isActive() { return forwardActive; }

    @Override
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

        // tick 所有执行器
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RouteExecutor> entry : executors.entrySet()) {
            RouteExecutor executor = entry.getValue();
            executor.tick(client);

        // 每趟换层：在到达任一端点时触发，不是只在整条路径跑完时。
        // 无限循环（loopCount=0）时，每次走完一趟导航点都会触发。
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

        // 处理跳跃请求
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

    /** 切维度、断开连接或加载世界时调用。 */
    @Override
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
