package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.FeatureRegistry;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Bridges malilib's tick and world-load events to {@link FeatureRegistry}, and turns the movement
 * features' desired input into held keys.
 *
 * <p>Key presses are declared through {@link SimulatedInput} and committed once, at the end of the
 * tick, after every feature has run — so container opening and shulker mining cannot fight
 * playback over the same key.
 */
public class ClientEventHandler {

    /** Owner token for the holds this class declares. */
    private static final Object MOVEMENT_OWNER = new Object();

    public static void register() {
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(new WorldLoadListener());
        TickHandler.getInstance().registerClientTickHandler(new PlayerControlTickHandler());
    }

    private static class WorldLoadListener implements IWorldLoadListener {
        @Override
        public void onWorldLoadPre(ClientLevel world1, ClientLevel world2, Minecraft client) {
            FeatureRegistry.notifyWorldChange();
            // No feature is simulating input across a world change; drop every hold so nothing
            // carries a pressed key into the new world.
            SimulatedInput.clear();
            SimulatedInput.apply();
        }
    }

    private static class PlayerControlTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(Minecraft mc) {
            if (mc.player == null) {
                SimulatedInput.clear();
                SimulatedInput.apply();
                return;
            }

            FeatureRegistry.tickAll(mc);

            InputPlayer playback = RecordingManager.getInstance().getPlayer();
            declareMovementKeys(mc, playback);

            if (playback.isPlaying()) {
                playback.applyYaw(mc);
            }

            // The single point where declared state reaches the KeyMappings.
            SimulatedInput.apply();
        }

        /**
         * Re-declare, from scratch, every key the movement features want this tick.
         *
         * <p>Starting from a clean slate matters: the playback branch touches nine keys and the
         * auto-forward branch only two, so leaving the previous tick's declarations in place would
         * keep strafe or sneak held after playback ended while auto-forward was still on.
         */
        private void declareMovementKeys(Minecraft mc, InputPlayer playback) {
            SimulatedInput.releaseAll(MOVEMENT_OWNER);

            if (playback.isPlaying()) {
                float fwd = playback.getForward();
                float side = playback.getSideways();
                SimulatedInput.set(mc.options.keyUp, MOVEMENT_OWNER, fwd > 0);
                SimulatedInput.set(mc.options.keyDown, MOVEMENT_OWNER, fwd < 0);
                SimulatedInput.set(mc.options.keyLeft, MOVEMENT_OWNER, side > 0);
                SimulatedInput.set(mc.options.keyRight, MOVEMENT_OWNER, side < 0);
                SimulatedInput.set(mc.options.keyJump, MOVEMENT_OWNER, playback.getJump());
                SimulatedInput.set(mc.options.keyShift, MOVEMENT_OWNER, playback.getSneak());
                SimulatedInput.set(mc.options.keySprint, MOVEMENT_OWNER, playback.getSprint());
                SimulatedInput.set(mc.options.keyAttack, MOVEMENT_OWNER, playback.getLeftClick());
                SimulatedInput.set(mc.options.keyUse, MOVEMENT_OWNER, playback.getRightClick());
                return;
            }

            RouteFlowRuntime routes = RouteFlowRuntime.getInstance();
            if (AutoForwardFeature.isEnabled() || routes.isForwardActive()) {
                SimulatedInput.hold(mc.options.keyUp, MOVEMENT_OWNER);
                SimulatedInput.set(mc.options.keySprint, MOVEMENT_OWNER, routes.isSprintRequested());
            }
        }
    }
}
