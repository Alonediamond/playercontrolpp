package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.AutoWaterFillFeature;
import com.alonediamond.playercontrolpp.feature.QuickTurnFeature;
import com.alonediamond.playercontrolpp.feature.SchematicSelectionContainerCacheFeature;
import com.alonediamond.playercontrolpp.gui.PlayerControlppConfigGui;
import com.alonediamond.playercontrolpp.record.InputRecorder;
import com.alonediamond.playercontrolpp.record.RecordingFile;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.AUTO_CACHE_NEARBY_CONTAINERS;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.AUTO_FORWARD;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.CACHE_SCHEMATIC_SELECTION_CONTAINERS;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.OPEN_CONFIG_GUI;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.QUICK_TURN;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.RECORDING_TOGGLE;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.WATER_FILL_TOGGLE;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.BARITONE_AUTO_GATHER;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.MARK_CONTAINER;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.ONE_CLICK_BUILD_RESTOCK;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoRestockFeature;

public class KeybindCallbacks {

    public static void register() {
        OPEN_CONFIG_GUI.getKeybind().setCallback(new OpenConfigGuiCallback());
        AUTO_FORWARD.getKeybind().setCallback(new AutoForwardCallback());
        QUICK_TURN.getKeybind().setCallback(new QuickTurnCallback());
        RECORDING_TOGGLE.getKeybind().setCallback(new RecordingToggleCallback());
        BARITONE_AUTO_GATHER.getKeybind().setCallback(new BaritoneAutoGatherCallback());
        AUTO_CACHE_NEARBY_CONTAINERS.getKeybind().setCallback(new AutoCacheNearbyContainersCallback());
        CACHE_SCHEMATIC_SELECTION_CONTAINERS.getKeybind().setCallback(
                new CacheSchematicSelectionContainersCallback());
        WATER_FILL_TOGGLE.getKeybind().setCallback(new WaterFillToggleCallback());
        MARK_CONTAINER.getKeybind().setCallback(new MarkContainerCallback());
        ONE_CLICK_BUILD_RESTOCK.getKeybind().setCallback(new OneClickBuildRestockCallback());

        // 路径热键的回调由 RouteManager 在路径被创建或加载时挂上——那是它们唯一存在的时刻。
        // 这里早先还有第二个循环，但它跑在 loadRoutes() 之前，遍历的永远是空列表。
    }

    private static class AutoForwardCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            Minecraft client = Minecraft.getInstance();
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
            Minecraft client = Minecraft.getInstance();
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
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return false;
            }
            ScreenCompat.setScreen(client, new PlayerControlppConfigGui(null));
            return true;
        }
    }

    private static class RecordingToggleCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return false;

            InputRecorder rec = RecordingManager.getInstance().getRecorder();
            if (rec.isRecording()) {
                RecordingFile rf = rec.stopRecording();
                RecordingManager.getInstance().addRecording(rf);
            } else {
                // 回放期间（含还在加载）禁止开始新录制
                if (RecordingManager.getInstance().getPlayer().isBusy()) return false;
                rec.startRecording(StringUtils.translate("playercontrolpp.gui.recording.new_recording"));
                ScreenCompat.setScreen(client, null); // 退出所有界面
            }
            return true;
        }
    }

    private static class BaritoneAutoGatherCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return false;
            AutoMaterialGatherer.getInstance().toggle();
            return true;
        }
    }

    private static class AutoCacheNearbyContainersCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return false;
            AutoCacheNearbyContainersFeature.toggle(client);
            return true;
        }
    }

    private static class CacheSchematicSelectionContainersCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return false;
            SchematicSelectionContainerCacheFeature.startOrCancel(client);
            return true;
        }
    }

    private static class WaterFillToggleCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return false;
            AutoWaterFillFeature.toggle(client);
            return true;
        }
    }

    private static class MarkContainerCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            AutoRestockFeature.onMarkContainer();
            return true;
        }
    }

    private static class OneClickBuildRestockCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) return false;
            AutoRestockFeature.onOneClickBuild();
            return true;
        }
    }

}
