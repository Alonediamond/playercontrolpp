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
     * 磁盘格式改到需要迁移时就 +1。目前还没有需要迁移的东西；写入并检查这个版本号，
     * 是为了让来自更新版本的配置被显式标记出来，而不是被静默地读一半。
     */
    private static final int CONFIG_VERSION = 1;

    // malilib 的所有翻译键都由这些前缀推导，写错只会静默得到一个未翻译的选项而不是报错。
    // 每个前缀一个常量，所有地方都引用它。
    private static final String KEY_HOTKEYS = Playercontrolpp.MOD_ID + ".config.hotkeys";
    private static final String KEY_SETTINGS = Playercontrolpp.MOD_ID + ".config.settings";
    private static final String KEY_BARITONE = Playercontrolpp.MOD_ID + ".config.baritone";
    private static final String KEY_CACHE_NEARBY = Playercontrolpp.MOD_ID + ".config.cache_nearby";

    /**
     * 所有潜影盒物品 id：未染色的那个加每种染料色一个。
     *
     * <p>由 {@link DyeColor} 生成而不是手写两遍——下面两个列表早先各重复了同样的十七个 id，
     * 加一种颜色得记着两边都改。
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

        public static final ConfigHotkey CACHE_SCHEMATIC_SELECTION_CONTAINERS = new ConfigHotkey(
                "cacheSchematicSelectionContainers", "",
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

        /** 热键集合的唯一真源。 */
        public static final ImmutableList<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE,
                BARITONE_AUTO_GATHER, AUTO_CACHE_NEARBY_CONTAINERS,
                CACHE_SCHEMATIC_SELECTION_CONTAINERS, WATER_FILL_TOGGLE, MARK_CONTAINER,
                ONE_CLICK_BUILD_RESTOCK);

        /** 同一批热键当普通配置看；由上面推导而来，两个列表不会走偏。 */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.copyOf(HOTKEY_LIST);
    }

    public static class Settings {
        public static final ConfigInteger TURN_ANGLE = new ConfigInteger(
                "turnAngle", 180, 0, 360, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger CACHE_DELAY = new ConfigInteger(
                "cacheDelay", 1, 0, 200, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger WATER_FILL_SCAN_RADIUS = new ConfigInteger(
                "waterFillScanRadius", 5, 0, 5, false)
                .apply(KEY_SETTINGS);

        public static final ConfigInteger WATER_FILL_OPERATION_DELAY = new ConfigInteger(
                "waterFillOperationDelay", 1, 1, 200, false)
                .apply(KEY_SETTINGS);

        /**
         * 刻意默认关闭。回放复现的是输入而不是坐标，有些偏差属于正常；把客户端坐标拉回录制位置
         * 会与服务端权威位置矛盾，看起来像移动作弊。只在单人世界、或明确可接受的场合开启。
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
        /**
         * 备货读哪份材料清单。GUI 里只在装了 LitematList 时才显示这项；
         * 模组被卸载而配置还停在 LITEMATLIST 时，由 MaterialAnalyzer 报错停机。
         */
        public static final ConfigOptionList MATERIAL_LIST_SOURCE = new ConfigOptionList(
                "materialListSource", MaterialSource.LITEMATICA,
                "Which material list the auto-gatherer reads.\nLitematica: the schematic info HUD's list, even if LitematList has taken it over.\nLitematList: the list uploaded in LitematList; items ignored there are not gathered.")
                .apply(KEY_BARITONE);

        /**
         * 缺口超过这个数量时，箱子追踪缓存里若有装着所需材料的整盒（潜影盒）就优先搬整盒，
         * 没有整盒才按组取散装。上限 1728 = 一个满盒的容量。
         */
        public static final ConfigInteger SHULKER_BOX_PRIORITY_THRESHOLD = new ConfigInteger(
                "shulkerBoxPriorityThreshold", 864, 64, 1728,
                "When a material's shortage exceeds this amount and ChestTracker's cache holds whole shulker boxes containing it, whole boxes are fetched first instead of loose stacks. Falls back to loose stacks when the cache has no such boxes. Default: 864.")
                .apply(KEY_BARITONE);

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

        /** 前两项属于「自动投影材料备货」，排在最前，GUI 里正好落在备货热键的下面。 */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                MATERIAL_LIST_SOURCE, SHULKER_BOX_PRIORITY_THRESHOLD,
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

        public static final ConfigInteger RESTOCK_STACKS_PER_ITEM = new ConfigInteger(
                "restockStacksPerItem", 8, 1, 36,
                "How many stacks of each missing material to top up to per restock trip. Litematica reports what the whole schematic still needs, which is usually far more than an inventory holds, so the target is capped at this many stacks per item type to leave room for the other materials.")
                .apply(KEY_BARITONE);

        public static final ConfigInteger RESTOCK_CONTAINER_MAX_DISTANCE = new ConfigInteger(
                "restockContainerMaxDistance", 0, 0, 1000,
                "Marked containers further than this many blocks from the player are ignored (no pathing). 0 means no limit.")
                .apply(KEY_BARITONE);

        public static final ConfigStringList MARKED_CONTAINERS = new ConfigStringList(
                "markedContainers", ImmutableList.of(),
                "Marked container positions for auto-restock. Each entry: dimension x y z [off] (e.g. minecraft:overworld 10 64 -20). A trailing 'off' disables that container without deleting it. Use the Mark Container hotkey to add/remove, sneak + the hotkey to enable/disable, or edit this list directly.")
                .apply(KEY_BARITONE);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                RESTOCK_SHULKER_MODE, RESTOCK_STACKS_PER_ITEM, RESTOCK_CONTAINER_MAX_DISTANCE,
                MARKED_CONTAINERS);
    }

    private static ImmutableList<String> withWaterBucket(List<String> ids) {
        return ImmutableList.<String>builder().add("minecraft:water_bucket").addAll(ids).build();
    }

    /** 所有带物品栏的原版方块，加全部颜色的潜影盒。 */
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
            // 之后每次保存都会同样失败，所以说一次，而不是静默 return。
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
        // 路径热键在同一个 GUI 里编辑，但数据存在路径文件里，saveToFile() 不碰那个文件——
        // 没有这一行，在「路径热键」标签页绑的键在重启游戏前有效，重启后又变成未绑定。
        RouteManager.getInstance().saveRoutes();
    }
}
