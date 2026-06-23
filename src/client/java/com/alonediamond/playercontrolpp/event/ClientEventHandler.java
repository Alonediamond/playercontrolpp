package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.AutoWaterFillFeature;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.PlayerInput;

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
            AutoWaterFillFeature.onWorldChange();
        }
    }

    private static class RouteTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(MinecraftClient mc) {
            RouteFlowRuntime.getInstance().onClientTick(mc);
            RecordingManager.getInstance().onClientTick(mc);
            AutoMaterialGatherer.getInstance().tick(mc);
            AutoCacheNearbyContainersFeature.tick(mc);
            AutoWaterFillFeature.tick(mc);
            // Apply playback yaw after input processing
            if (RecordingManager.getInstance().getPlayer().isPlaying()) {
                RecordingManager.getInstance().getPlayer().applyYaw(mc);
            }
            // Handle playback left/right click + direct input (works even when GUI is open)
            InputPlayer player = RecordingManager.getInstance().getPlayer();
            if (player.isPlaying() && mc.player != null) {
                mc.options.attackKey.setPressed(player.getLeftClick());
                mc.options.useKey.setPressed(player.getRightClick());
                // Apply movement directly. The MixinKeyboardInput handles this when
                // no GUI is open, but when a GUI (RouteListGui, RecordingListGui, etc.)
                // is open, KeyboardInput.tick() never fires. Setting input here
                // ensures movement continues regardless of GUI state.
                mc.player.input.movementForward = player.getForward();
                mc.player.input.movementSideways = player.getSideways();
                mc.player.input.playerInput = new PlayerInput(
                        player.getForward() > 0,
                        player.getForward() < 0,
                        player.getSideways() < 0,
                        player.getSideways() > 0,
                        player.getJump(),
                        player.getSneak(),
                        player.getSprint()
                );
            }
        }
    }
}
