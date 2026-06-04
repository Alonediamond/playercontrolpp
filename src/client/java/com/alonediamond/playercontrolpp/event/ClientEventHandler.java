package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public class ClientEventHandler {

    public static void register() {
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(new WorldLoadListener());
        TickHandler.getInstance().registerClientTickHandler(new RouteTickHandler());
    }

    private static class WorldLoadListener implements IWorldLoadListener {
        @Override
        public void onWorldLoadPre(ClientWorld world1, ClientWorld world2, MinecraftClient client) {
            AutoForwardFeature.onWorldChange();
            RouteFlowRuntime.getInstance().onWorldChange();
            AutoMaterialGatherer.getInstance().onWorldChange();
            AutoCacheNearbyContainersFeature.onWorldChange();
        }
    }

    private static class RouteTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(MinecraftClient mc) {
            RouteFlowRuntime.getInstance().onClientTick(mc);
            RecordingManager.getInstance().onClientTick(mc);
            AutoMaterialGatherer.getInstance().tick(mc);
            AutoCacheNearbyContainersFeature.tick(mc);
            // Apply playback yaw after input processing
            if (RecordingManager.getInstance().getPlayer().isPlaying()) {
                RecordingManager.getInstance().getPlayer().applyYaw(mc);
            }
            // Handle playback left/right click
            InputPlayer player = RecordingManager.getInstance().getPlayer();
            if (player.isPlaying()) {
                mc.options.attackKey.setPressed(player.getLeftClick());
                mc.options.useKey.setPressed(player.getRightClick());
            }
        }
    }
}
