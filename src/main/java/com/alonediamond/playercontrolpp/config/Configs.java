package com.alonediamond.playercontrolpp.config;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.MaLiLibCompat;
import com.alonediamond.playercontrolpp.route.RouteManager;

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
import net.minecraft.world.item.DyeColor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Configs implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = "playercontrolpp.json";

    /**
     * Bumped when the on-disk layout changes in a way that needs migrating. Nothing needs
     * migrating yet; the version is written and checked so a config from a future build is
     * flagged instead of being silently half-read.
     */
    private static final int CONFIG_VERSION = 1;

    // malilib derives every translation key from these prefixes, so a typo silently yields an
    // untranslated option rather than an error. One constant each, referenced everywhere.
    private static final String KEY_HOTKEYS = Playercontrolpp.MOD_ID + ".config.hotkeys";
    private static final String KEY_SETTINGS = Playercontrolpp.MOD_ID + ".config.settings";
    private static final String KEY_BARITONE = Playercontrolpp.MOD_ID + ".config.baritone";
    private static final String KEY_CACHE_NEARBY = Playercontrolpp.MOD_ID + ".config.cache_nearby";

    /**
     * Every shulker box item id: the undyed one plus one per dye colour.
     *
     * <p>Generated from {@link DyeColor} rather than written out twice by hand — the two lists
     * below used to repeat the same seventeen ids, so adding a colour meant remembering both.
     */
    private static final ImmutableList<String> ALL_SHULKER_BOX_IDS = allShulkerBoxIds();

    private static ImmutableList<String> allShulkerBoxIds() {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        builder.add("minecraft:shulker_box");
        for (DyeColor color : DyeColor.values()) {
            builder.add("minecraft:" + color.getSerializedName() + "_shulker_box");
        }
        return builder.build();
    }

    public static class Hotkeys {
        public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey(
                "openConfigGui", "P,C",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey AUTO_FORWARD = new ConfigHotkey(
                "autoForward", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey QUICK_TURN = new ConfigHotkey(
                "quickTurn", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey RECORDING_TOGGLE = new ConfigHotkey(
                "recordingToggle", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey BARITONE_AUTO_GATHER = new ConfigHotkey(
                "baritoneAutoGather", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey AUTO_CACHE_NEARBY_CONTAINERS = new ConfigHotkey(
                "autoCacheNearbyContainers", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey WATER_FILL_TOGGLE = new ConfigHotkey(
                "waterFillToggle", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey MARK_CONTAINER = new ConfigHotkey(
                "markContainer", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        public static final ConfigHotkey ONE_CLICK_BUILD_RESTOCK = new ConfigHotkey(
                "oneClickBuildRestock", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        /** The single source of truth for the hotkey set. */
        public static final ImmutableList<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE,
                BARITONE_AUTO_GATHER, AUTO_CACHE_NEARBY_CONTAINERS, WATER_FILL_TOGGLE,
                MARK_CONTAINER, ONE_CLICK_BUILD_RESTOCK);

        /** Same hotkeys seen as plain configs; derived so the two lists cannot diverge. */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.copyOf(HOTKEY_LIST);
    }

    public static class Settings {
        public static final ConfigInteger TURN_ANGLE = new ConfigInteger(
                "turnAngle", 180, 0, 360, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger CACHE_DELAY = new ConfigInteger(
                "cacheDelay", 1, 1, 200, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger WATER_FILL_SCAN_RADIUS = new ConfigInteger(
                "waterFillScanRadius", 5, 0, 5, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger WATER_FILL_OPERATION_DELAY = new ConfigInteger(
                "waterFillOperationDelay", 1, 1, 200, false)
                .apply(KEY_SETTINGS);

        /**
         * Off by default on purpose. Playback reproduces input, not positions, so some drift is
         * normal; snapping the client's position back to the recorded one contradicts the
         * server's authoritative position and looks like a movement cheat. Enable it only in
         * single-player or where that is acceptable.
         */
        public static final ConfigBoolean PLAYBACK_POSITION_CORRECTION = new ConfigBoolean(
                "playbackPositionCorrection", false,
                "Teleport the player back onto the recorded path when playback drifts more than "
                        + "2 blocks. Off by default: it desyncs from the server's position and "
                        + "anti-cheat may read it as flying.")
                .apply(KEY_SETTINGS);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                TURN_ANGLE, CACHE_DELAY, WATER_FILL_SCAN_RADIUS, WATER_FILL_OPERATION_DELAY,
                PLAYBACK_POSITION_CORRECTION);
    }

    public static class BaritoneSettings {
        public static final ConfigBoolean ENABLE_GLOBAL_IGNORE = new ConfigBoolean(
                "enableGlobalIgnore", false,
                "When enabled, items in the Global Ignore List will be skipped during auto-gathering.")
                .apply(KEY_BARITONE);

        public static final ConfigBoolean AUTO_STORE_TO_SHULKER = new ConfigBoolean(
                "autoStoreToShulker", false,
                "When enabled, automatically store gathered building materials into shulker boxes when inventory is full, then resume auto-gathering.")
                .apply(KEY_BARITONE);

        public static final ConfigOptionList SHULKER_STORAGE_MODE = new ConfigOptionList(
                "shulkerStorageMode", StorageMode.SIMULATE,
                "How to store materials into shulker boxes.\nSimulate: place/open/mine the shulker box.\nQuickShulker: open directly from inventory via QuickShulker API.")
                .apply(KEY_BARITONE);

        public static final ConfigStringList GLOBAL_IGNORE_LIST = new ConfigStringList(
                "globalIgnoreList", withWaterBucket(ALL_SHULKER_BOX_IDS),
                "Item IDs to ignore during auto-gathering. Edit via the GUI button or click to open the list editor.")
                .apply(KEY_BARITONE);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLE_GLOBAL_IGNORE, AUTO_STORE_TO_SHULKER, SHULKER_STORAGE_MODE, GLOBAL_IGNORE_LIST);
    }

    public static class CacheNearbySettings {
        public static final ConfigStringList CONTAINER_WHITELIST = new ConfigStringList(
                "containerWhitelist", defaultContainerWhitelist(),
                "Block IDs of containers that can be auto-cached. Edit via the GUI button or click to open the list editor.")
                .apply(KEY_CACHE_NEARBY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                CONTAINER_WHITELIST);
    }

    public static class Restocks {
        public static final ConfigBoolean RESTOCK_SHULKER_MODE = new ConfigBoolean(
                "restockShulkerMode", false,
                "When enabled, auto-restock will also take shulker boxes that contain needed materials from marked containers. Requires QuickShulker to be useful after collecting.")
                .apply(KEY_BARITONE);

        public static final ConfigStringList MARKED_CONTAINERS = new ConfigStringList(
                "markedContainers", ImmutableList.of(),
                "Marked container positions for auto-restock. Each entry: dimension x y z (e.g. minecraft:overworld 10 64 -20). Use the Mark Container hotkey to add/remove, or edit this list directly.")
                .apply(KEY_BARITONE);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                RESTOCK_SHULKER_MODE, MARKED_CONTAINERS);
    }

    private static ImmutableList<String> withWaterBucket(List<String> ids) {
        return ImmutableList.<String>builder().add("minecraft:water_bucket").addAll(ids).build();
    }

    /** Every vanilla block with an inventory, plus all the shulker box colours. */
    private static ImmutableList<String> defaultContainerWhitelist() {
        return ImmutableList.<String>builder()
                .add("minecraft:chest",
                        "minecraft:trapped_chest",
                        "minecraft:ender_chest",
                        "minecraft:barrel",
                        "minecraft:hopper",
                        "minecraft:dispenser",
                        "minecraft:dropper",
                        "minecraft:furnace",
                        "minecraft:blast_furnace",
                        "minecraft:smoker",
                        "minecraft:brewing_stand")
                .addAll(ALL_SHULKER_BOX_IDS)
                .build();
    }

    public static void loadFromFile() {
        Path configFile = MaLiLibCompat.configDirectory().resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configFile) || Files.isDirectory(configFile)) return;

        JsonElement element = MaLiLibCompat.parseJsonFile(configFile);
        if (element == null || !element.isJsonObject()) {
            Playercontrolpp.LOGGER.warn("{} is not readable JSON; keeping default settings",
                    CONFIG_FILE_NAME);
            return;
        }

        JsonObject root = element.getAsJsonObject();
        int fileVersion = root.has("configVersion") ? root.get("configVersion").getAsInt() : CONFIG_VERSION;
        if (fileVersion > CONFIG_VERSION) {
            Playercontrolpp.LOGGER.warn(
                    "{} was written by a newer version of the mod (config version {} > {}); "
                            + "unknown settings will be dropped on the next save",
                    CONFIG_FILE_NAME, fileVersion, CONFIG_VERSION);
        }

        ConfigUtils.readConfigBase(root, "Settings", Settings.OPTIONS);
        ConfigUtils.readConfigBase(root, "BaritoneSettings", BaritoneSettings.OPTIONS);
        ConfigUtils.readConfigBase(root, "RestockSettings", Restocks.OPTIONS);
        ConfigUtils.readConfigBase(root, "CacheNearbySettings", CacheNearbySettings.OPTIONS);
        ConfigUtils.readHotkeys(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
    }

    public static void saveToFile() {
        Path dir = MaLiLibCompat.configDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            // Every later save will fail too, so say so once rather than returning in silence.
            Playercontrolpp.LOGGER.warn("Cannot create the config directory {}; settings will not persist",
                    dir, e);
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("configVersion", CONFIG_VERSION);
        ConfigUtils.writeConfigBase(root, "Settings", Settings.OPTIONS);
        ConfigUtils.writeConfigBase(root, "BaritoneSettings", BaritoneSettings.OPTIONS);
        ConfigUtils.writeConfigBase(root, "RestockSettings", Restocks.OPTIONS);
        ConfigUtils.writeConfigBase(root, "CacheNearbySettings", CacheNearbySettings.OPTIONS);
        ConfigUtils.writeHotkeys(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
        MaLiLibCompat.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
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
        // Route hotkeys are edited in the same GUI but live in the routes file, which
        // saveToFile() does not touch — without this, a key bound on the Route Hotkeys tab
        // worked until the game was restarted and then came back unbound.
        RouteManager.getInstance().saveRoutes();
    }
}
