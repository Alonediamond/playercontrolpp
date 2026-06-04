package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.QuickTurnFeature;
import com.alonediamond.playercontrolpp.gui.PlayerControlppConfigGui;
import com.alonediamond.playercontrolpp.record.InputRecorder;
import com.alonediamond.playercontrolpp.record.RecordingFile;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;

import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.AUTO_CACHE_NEARBY_CONTAINERS;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.AUTO_FORWARD;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.OPEN_CONFIG_GUI;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.QUICK_TURN;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.RECORDING_TOGGLE;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.BARITONE_AUTO_GATHER;

public class KeybindCallbacks {

    public static void register() {
        OPEN_CONFIG_GUI.getKeybind().setCallback(new OpenConfigGuiCallback());
        AUTO_FORWARD.getKeybind().setCallback(new AutoForwardCallback());
        QUICK_TURN.getKeybind().setCallback(new QuickTurnCallback());
        RECORDING_TOGGLE.getKeybind().setCallback(new RecordingToggleCallback());
        BARITONE_AUTO_GATHER.getKeybind().setCallback(new BaritoneAutoGatherCallback());
        AUTO_CACHE_NEARBY_CONTAINERS.getKeybind().setCallback(new AutoCacheNearbyContainersCallback());

        // Register route hotkey callbacks
        for (RouteManager.RouteHotkey rh : RouteManager.getInstance().getRouteHotkeyList()) {
            rh.getKeybind().setCallback(new RouteToggleCallback(rh));
        }
    }

    private static class AutoForwardCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            AutoForwardFeature.toggle(client);
            return true;
        }
    }

    private static class QuickTurnCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            QuickTurnFeature.execute(client);
            return true;
        }
    }

    private static class OpenConfigGuiCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            client.setScreen(new PlayerControlppConfigGui(null));
            return true;
        }
    }

    private static class RecordingToggleCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return false;

            InputRecorder rec = RecordingManager.getInstance().getRecorder();
            if (rec.isRecording()) {
                RecordingFile rf = rec.stopRecording();
                RecordingManager.getInstance().addRecording(rf);
            } else {
                // Prevent recording during playback
                if (RecordingManager.getInstance().getPlayer().isPlaying()) return false;
                rec.startRecording(StringUtils.translate("playercontrolpp.gui.recording.new_recording"), false);
                client.setScreen(null); // exit all GUIs
            }
            return true;
        }
    }

    private static class BaritoneAutoGatherCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return false;
            AutoMaterialGatherer.getInstance().toggle();
            return true;
        }
    }

    private static class AutoCacheNearbyContainersCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return false;
            AutoCacheNearbyContainersFeature.toggle(client);
            return true;
        }
    }

    private static class RouteToggleCallback implements IHotkeyCallback {
        private final RouteManager.RouteHotkey routeHotkey;

        RouteToggleCallback(RouteManager.RouteHotkey routeHotkey) {
            this.routeHotkey = routeHotkey;
        }

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            RouteManager.getInstance().getRoutes().stream()
                    .filter(r -> r.getId().equals(routeHotkey.getRoute().getId()))
                    .findFirst()
                    .ifPresent(r -> com.alonediamond.playercontrolpp.route.RouteFlowRuntime
                            .getInstance().toggleRoute(r));
            return true;
        }
    }
}
