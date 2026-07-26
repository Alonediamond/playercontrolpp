package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.AutoWaterFillFeature;
import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class ClientEventHandler {

    private static boolean wasSimulating;

    public static void register() {
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(new WorldLoadListener());
        TickHandler.getInstance().registerClientTickHandler(new RouteTickHandler());
    }

    private static class WorldLoadListener implements IWorldLoadListener {
        @Override
        public void onWorldLoadPre(ClientLevel world1, ClientLevel world2, Minecraft client) {
            AutoForwardFeature.onWorldChange();
            RouteFlowRuntime.getInstance().onWorldChange();
            AutoMaterialGatherer.getInstance().onWorldChange();
            AutoCacheNearbyContainersFeature.onWorldChange();
            AutoWaterFillFeature.onWorldChange();
        }
    }

    private static class RouteTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(Minecraft mc) {
            if (mc.player == null) return;

            RouteFlowRuntime.getInstance().onClientTick(mc);
            RecordingManager.getInstance().onClientTick(mc);
            AutoMaterialGatherer.getInstance().tick(mc);
            AutoCacheNearbyContainersFeature.tick(mc);
            AutoWaterFillFeature.tick(mc);

            InputPlayer playback = RecordingManager.getInstance().getPlayer();
            boolean playbackActive = playback.isPlaying();
            boolean autoForward = AutoForwardFeature.isEnabled();
            boolean routeForward = RouteFlowRuntime.getInstance().isForwardActive();
            boolean routeSprint = RouteFlowRuntime.getInstance().isSprintRequested();
            boolean simulating = playbackActive || autoForward || routeForward;

            if (simulating) {
                simulateKeys(mc, playbackActive, playback, autoForward, routeForward, routeSprint);
                wasSimulating = true;
            } else if (wasSimulating) {
                releaseAllKeys(mc);
                wasSimulating = false;
            }

            // Apply playback yaw/pitch
            if (playbackActive) {
                playback.applyYaw(mc);
            }
        }

        private void simulateKeys(Minecraft mc, boolean playbackActive, InputPlayer playback,
                                  boolean autoForward, boolean routeForward, boolean routeSprint) {
            if (playbackActive) {
                // Full key simulation from playback values
                float fwd = playback.getForward();
                float side = playback.getSideways();
                mc.options.keyUp.setDown(fwd > 0);
                mc.options.keyDown.setDown(fwd < 0);
                mc.options.keyLeft.setDown(side > 0);
                mc.options.keyRight.setDown(side < 0);
                mc.options.keyJump.setDown(playback.getJump());
                mc.options.keyShift.setDown(playback.getSneak());
                mc.options.keySprint.setDown(playback.getSprint());
                mc.options.keyAttack.setDown(playback.getLeftClick());
                mc.options.keyUse.setDown(playback.getRightClick());
            } else {
                // Auto-forward or route-forward
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(routeSprint);
            }
        }

        private void releaseAllKeys(Minecraft mc) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
        }
    }
}
