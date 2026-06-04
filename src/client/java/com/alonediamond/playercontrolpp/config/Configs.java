package com.alonediamond.playercontrolpp.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
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

        public static final ConfigHotkey BARITONE_AUTO_GATHER = new ConfigHotkey(
                "baritoneAutoGather", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ConfigHotkey AUTO_CACHE_NEARBY_CONTAINERS = new ConfigHotkey(
                "autoCacheNearbyContainers", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply("playercontrolpp.config.hotkeys");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE,
                BARITONE_AUTO_GATHER, AUTO_CACHE_NEARBY_CONTAINERS);

        public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE,
                BARITONE_AUTO_GATHER, AUTO_CACHE_NEARBY_CONTAINERS);
    }

    public static class Settings {
        public static final ConfigInteger TURN_ANGLE = new ConfigInteger(
                "turnAngle", 180, 0, 360, false)
                .apply("playercontrolpp.config.settings");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                TURN_ANGLE);
    }

    public static class BaritoneSettings {
        public static final ConfigBoolean ENABLE_GLOBAL_IGNORE = new ConfigBoolean(
                "enableGlobalIgnore", false,
                "When enabled, items in the Global Ignore List will be skipped during auto-gathering.")
                .apply("playercontrolpp.config.baritone");

        public static final ConfigBoolean AUTO_STORE_TO_SHULKER = new ConfigBoolean(
                "autoStoreToShulker", false,
                "When enabled, automatically store gathered building materials into shulker boxes when inventory is full, then resume auto-gathering.")
                .apply("playercontrolpp.config.baritone");

        public static final ConfigOptionList SHULKER_STORAGE_MODE = new ConfigOptionList(
                "shulkerStorageMode", StorageMode.SIMULATE,
                "How to store materials into shulker boxes.\nSimulate: place/open/mine the shulker box.\nQuickShulker: open directly from inventory via QuickShulker API.")
                .apply("playercontrolpp.config.baritone");

        public static final ConfigStringList GLOBAL_IGNORE_LIST = new ConfigStringList(
                "globalIgnoreList", ImmutableList.of(
                        "minecraft:water_bucket",
                        "minecraft:shulker_box",
                        "minecraft:white_shulker_box",
                        "minecraft:orange_shulker_box",
                        "minecraft:magenta_shulker_box",
                        "minecraft:light_blue_shulker_box",
                        "minecraft:yellow_shulker_box",
                        "minecraft:lime_shulker_box",
                        "minecraft:pink_shulker_box",
                        "minecraft:gray_shulker_box",
                        "minecraft:light_gray_shulker_box",
                        "minecraft:cyan_shulker_box",
                        "minecraft:purple_shulker_box",
                        "minecraft:blue_shulker_box",
                        "minecraft:brown_shulker_box",
                        "minecraft:green_shulker_box",
                        "minecraft:red_shulker_box",
                        "minecraft:black_shulker_box"
                ),
                "Item IDs to ignore during auto-gathering. Edit via the GUI button or click to open the list editor.")
                .apply("playercontrolpp.config.baritone");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLE_GLOBAL_IGNORE, AUTO_STORE_TO_SHULKER, SHULKER_STORAGE_MODE, GLOBAL_IGNORE_LIST);
    }

    public static class CacheNearbySettings {
        public static final ConfigStringList CONTAINER_WHITELIST = new ConfigStringList(
                "containerWhitelist", ImmutableList.of(
                        "minecraft:chest",
                        "minecraft:trapped_chest",
                        "minecraft:ender_chest",
                        "minecraft:barrel",
                        "minecraft:hopper",
                        "minecraft:dispenser",
                        "minecraft:dropper",
                        "minecraft:furnace",
                        "minecraft:blast_furnace",
                        "minecraft:smoker",
                        "minecraft:brewing_stand",
                        "minecraft:shulker_box",
                        "minecraft:white_shulker_box",
                        "minecraft:orange_shulker_box",
                        "minecraft:magenta_shulker_box",
                        "minecraft:light_blue_shulker_box",
                        "minecraft:yellow_shulker_box",
                        "minecraft:lime_shulker_box",
                        "minecraft:pink_shulker_box",
                        "minecraft:gray_shulker_box",
                        "minecraft:light_gray_shulker_box",
                        "minecraft:cyan_shulker_box",
                        "minecraft:purple_shulker_box",
                        "minecraft:blue_shulker_box",
                        "minecraft:brown_shulker_box",
                        "minecraft:green_shulker_box",
                        "minecraft:red_shulker_box",
                        "minecraft:black_shulker_box"
                ),
                "Block IDs of containers that can be auto-cached. Edit via the GUI button or click to open the list editor.")
                .apply("playercontrolpp.config.cache_nearby");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                CONTAINER_WHITELIST);
    }

    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);
        if (Files.exists(configFile) && !Files.isDirectory(configFile)) {
            JsonElement element = JsonUtils.parseJsonFile(configFile.toFile());
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Settings", Settings.OPTIONS);
                ConfigUtils.readConfigBase(root, "BaritoneSettings", BaritoneSettings.OPTIONS);
                ConfigUtils.readConfigBase(root, "CacheNearbySettings", CacheNearbySettings.OPTIONS);
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
        ConfigUtils.writeConfigBase(root, "BaritoneSettings", BaritoneSettings.OPTIONS);
        ConfigUtils.writeConfigBase(root, "CacheNearbySettings", CacheNearbySettings.OPTIONS);
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
