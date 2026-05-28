package com.alonediamond.playercontrolpp.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Configs implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = "playercontrolpp.json";
    private static final int CONFIG_VERSION = 1;

    public static class Hotkeys {
        public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey(
                "openConfigGui", "P,C",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ConfigHotkey AUTO_FORWARD = new ConfigHotkey(
                "autoForward", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ConfigHotkey QUICK_TURN = new ConfigHotkey(
                "quickTurn", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ConfigHotkey RECORDING_TOGGLE = new ConfigHotkey(
                "recordingToggle", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE);

        public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE);
    }

    public static class Settings {
        public static final ConfigInteger TURN_ANGLE = new ConfigInteger(
                "turnAngle", 180, 0, 360, false)
                .apply("playercontrolpp.config.settings");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                TURN_ANGLE);
    }

    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);
        if (Files.exists(configFile) && !Files.isDirectory(configFile)) {
            JsonElement element = JsonUtils.parseJsonFile(configFile.toFile());
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Settings", Settings.OPTIONS);
                ConfigUtils.readHotkeys(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            }
        }
    }

    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectoryAsPath();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            return;
        }
        Path configFile = dir.resolve(CONFIG_FILE_NAME);
        JsonObject root = new JsonObject();
        root.addProperty("configVersion", CONFIG_VERSION);
        ConfigUtils.writeConfigBase(root, "Settings", Settings.OPTIONS);
        ConfigUtils.writeHotkeys(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
        JsonUtils.writeJsonToFile(root, configFile.toFile());
    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    @Override
    public void onConfigsChanged() {
        saveToFile();
    }
}
