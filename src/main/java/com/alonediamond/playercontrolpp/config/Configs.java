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

        /** 对着方块按下，记录该工作台坐标；再按同一个方块取消记录。 */
        public static final ConfigHotkey MARK_CRAFTING_TABLE = new ConfigHotkey(
                "markCraftingTable", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        /** 对着方块按下，记录该切石机坐标；再按同一个方块取消记录。 */
        public static final ConfigHotkey MARK_STONECUTTER = new ConfigHotkey(
                "markStonecutter", "",
                KeybindSettings.PRESS_ALLOWEXTRA)
                .apply(KEY_HOTKEYS);

        /** 热键集合的唯一真源。 */
        public static final ImmutableList<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_CONFIG_GUI, AUTO_FORWARD, QUICK_TURN, RECORDING_TOGGLE,
                BARITONE_AUTO_GATHER, AUTO_CACHE_NEARBY_CONTAINERS,
                CACHE_SCHEMATIC_SELECTION_CONTAINERS, WATER_FILL_TOGGLE, MARK_CONTAINER,
                ONE_CLICK_BUILD_RESTOCK, MARK_CRAFTING_TABLE, MARK_STONECUTTER);

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

        public static final ConfigInteger CLICK_COOLDOWN = new ConfigInteger(
                "clickCooldown", 1, 0, 60,
                "Set the number of ticks for the cooldown interval between transferring items in the container each time."
        ).apply(KEY_BARITONE);

        /**
         * 备货读哪份材料清单。所有来源始终可见，依赖不足时启动检查会提示；
         * 模组被卸载而配置还停在 LITEMATLIST 时，由 MaterialListReader 返回错误并停机。
         */
        public static final ConfigOptionList MATERIAL_LIST_SOURCE = new ConfigOptionList(
                "materialListSource", MaterialSource.LITEMATICA,
                "Which material list the auto-gatherer reads.\nLitematica: the schematic info HUD's list, even if LitematList has taken it over.\nLitematList: the list uploaded in LitematList; items ignored there are not gathered.")
                .apply(KEY_BARITONE);

        /**
         * 缺口超过这个数量时，箱子追踪缓存里若有装着所需材料的整盒（潜影盒）就优先搬整盒，
         * 没有满盒时按实际缺口取散装。满盒容量按材料自身堆叠上限判定。
         */
        public static final ConfigInteger SHULKER_BOX_PRIORITY_THRESHOLD = new ConfigInteger(
                "shulkerBoxPriorityThreshold", 864, 64, 1728,
                "When a material's shortage exceeds this amount and ChestTracker's cache holds whole shulker boxes containing it, whole boxes are fetched first instead of loose stacks. Falls back to loose stacks when the cache has no such boxes. Default: 864.")
                .apply(KEY_BARITONE);

        /** 散装和可搬走的满盒不足时，借出含料盒精确取料，再归还原容器。 */
        public static final ConfigBoolean RECOGNIZE_MIXED_SHULKER_BOX = new ConfigBoolean(
                "recognizeMixedShulkerBox", false,
                "When a material's shortage is below the whole-box threshold and ChestTracker has no loose stock of it, look for shulker boxes containing it in cached containers: take the box out, open it, take out every missing material inside, then put the box back. Off: such materials are simply skipped, as before.")
                .apply(KEY_BARITONE);

        /**
         * 缓存里没有的材料要不要追溯原材料并合成；
         * 启用前提是备货数据来源选了「跟随LitematList清单」且那边已上传清单。
         */
        public static final ConfigOptionList RAW_MATERIAL_CRAFT_MODE = new ConfigOptionList(
                "rawMaterialCraftMode", RawMaterialMode.DISABLED,
                "Whether to trace and craft missing materials from their raw materials when ChestTracker has none.\nDisabled: skip the material, as before.\nCache Only: gather the raw materials from cached containers.\nWorld Search: also let Baritone mine/find them in the world.\nRequires LitematList as the material list source, with a list uploaded there.")
                .apply(KEY_BARITONE);

        /**
         * v1.9：原材料溯源层数。<b>-1 = 跟随 LitematList 原材料界面上的层数设置</b>（观察不到时用它的默认值 5），
         * 0-10 = 用本模组指定的层数。层数上限由 LitematList 自己限制在 0-10。
         */
        public static final ConfigInteger RAW_MATERIAL_MAX_DEPTH = new ConfigInteger(
                "rawMaterialMaxDepth", -1, -1, 10,
                "How many recipe layers to trace when decomposing a missing material into raw materials. -1 = follow the layer count set on LitematList's raw material screen (its default is 5); 0-10 = use this value instead.")
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

        /**
         * 关掉容器界面后等多少 tick 再继续。
         *
         * <p>关界面与随后的搜索/寻路都是独立的包，服务端还在处理"界面关闭"时就去开下一个容器，
         * 有概率被当成"界面已关"丢掉。延迟越高越稳，越低越快。
         */
        public static final ConfigInteger CONTAINER_CLOSE_DELAY = new ConfigInteger(
                "containerCloseDelay", 8, 0, 40,
                "Ticks to wait after closing a container before moving on. Raise this if clicks are dropped on a laggy server.")
                .apply(KEY_BARITONE);

        /**
         * 同一个容器里两次取物点击之间的间隔。
         *
         * <p>与跨容器的 {@link #CLICK_COOLDOWN} 分开：同一菜单内连续点击不受寻路影响，
         * 可以更激进；多人服务器同步慢时调大。
         */
        public static final ConfigInteger INTRA_CONTAINER_CLICK_DELAY = new ConfigInteger(
                "intraContainerClickDelay", 1, 0, 20,
                "Ticks between two item transfers inside the same open container. Separate from clickCooldown so one container can be drained quickly.")
                .apply(KEY_BARITONE);

        /**
         * 追溯时世界挖掘停滞多久换来源。
         *
         * <p>旧版固定 1200 tick（一分钟），挖不到时会长时间空转。调小更早换变体/换来源。
         */
        public static final ConfigInteger MINING_STALL_TICKS = new ConfigInteger(
                "miningStallTicks", 200, 40, 1200,
                "How many ticks Baritone may mine without the target material increasing before the raw-material trace switches to another source or variant.")
                .apply(KEY_BARITONE);

        /** 等价变体（木板/原木/圆石族）按什么顺序挑。 */
        public static final ConfigOptionList RAW_MATERIAL_VARIANT_MODE = new ConfigOptionList(
                "rawMaterialVariantMode", VariantMode.CACHE_COUNT,
                "How to pick between equivalent raw materials (any plank type, any log type, cobblestone family...).\nCache Count: use the variant with the most matches in ChestTracker's cache.\nTree Default: keep whatever LitematList's recipe tree picked.")
                .apply(KEY_BARITONE);

        /** 模拟模式放置潜影盒时的搜索半径（以玩家为中心）。 */
        public static final ConfigInteger SHULKER_PLACE_RADIUS = new ConfigInteger(
                "shulkerPlaceRadius", 3, 1, 5,
                "Radius (in blocks) searched for a place to put down a shulker box when storing materials.")
                .apply(KEY_BARITONE);

        /** 找不到放置位时是否允许让玩家/Baritone 先侧移一格再试。 */
        public static final ConfigBoolean ALLOW_NUDGE_FOR_PLACEMENT = new ConfigBoolean(
                "allowNudgeForPlacement", true,
                "When no legal spot to place a shulker box is found, briefly step aside (one block) and try again. Off: stop with a message instead.")
                .apply(KEY_BARITONE);

        /** 自动合成用哪种工作站来源。 */
        public static final ConfigOptionList CRAFT_STATION_SOURCE = new ConfigOptionList(
                "craftStationSource", CraftStationSource.AUTO,
                "Where the auto-crafter gets its crafting table / stonecutter.\nAuto: from the inventory via QuickShulker first, then the marked world position.\nInventory Only: QuickShulker only.\nWorld Position Only: walk to the position marked with the hotkeys.")
                .apply(KEY_BARITONE);

        /** 记录的工作台坐标，格式同标记容器：dimension x y z。 */
        public static final ConfigStringList CRAFTING_TABLE_POSITIONS = new ConfigStringList(
                "craftingTablePositions", ImmutableList.of(),
                "Marked crafting table positions for auto-crafting. Each entry: dimension x y z. Use the Mark Crafting Table hotkey to record one.")
                .apply(KEY_BARITONE);

        /** 记录的切石机坐标，格式同上。 */
        public static final ConfigStringList STONECUTTER_POSITIONS = new ConfigStringList(
                "stonecutterPositions", ImmutableList.of(),
                "Marked stonecutter positions for auto-crafting. Each entry: dimension x y z. Use the Mark Stonecutter hotkey to record one.")
                .apply(KEY_BARITONE);

        /** 未缓存容器高亮持续多少 tick；0 = 关闭。 */
        public static final ConfigInteger UNCACHED_HIGHLIGHT_TICKS = new ConfigInteger(
                "uncachedHighlightTicks", 100, 0, 600,
                "How long (in ticks) containers that failed to be cached stay highlighted in the world. 0 disables the highlight.")
                .apply(KEY_BARITONE);

        public static final ConfigStringList GLOBAL_IGNORE_LIST = new ConfigStringList(
                "globalIgnoreList", withWaterBucket(ALL_SHULKER_BOX_IDS),
                "Item IDs to ignore during auto-gathering. Edit via the GUI button or click to open the list editor.")
                .apply(KEY_BARITONE);

        /** 按清单、转移、潜影盒、追溯、合成站点、忽略项的顺序展示备货设置。 */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                MATERIAL_LIST_SOURCE, CLICK_COOLDOWN, INTRA_CONTAINER_CLICK_DELAY, CONTAINER_CLOSE_DELAY,
                SHULKER_BOX_PRIORITY_THRESHOLD, RECOGNIZE_MIXED_SHULKER_BOX,
                AUTO_STORE_TO_SHULKER, SHULKER_STORAGE_MODE,
                SHULKER_PLACE_RADIUS, ALLOW_NUDGE_FOR_PLACEMENT,
                RAW_MATERIAL_CRAFT_MODE, RAW_MATERIAL_MAX_DEPTH, RAW_MATERIAL_VARIANT_MODE, MINING_STALL_TICKS,
                CRAFT_STATION_SOURCE, CRAFTING_TABLE_POSITIONS, STONECUTTER_POSITIONS,
                UNCACHED_HIGHLIGHT_TICKS,
                ENABLE_GLOBAL_IGNORE, GLOBAL_IGNORE_LIST);
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
        public static final ConfigOptionList RESTOCK_CONTAINER_SOURCE = new ConfigOptionList(
                "restockContainerSource", RestockContainerSource.MARKED,
                "Container source for building restock: player marked coordinates or ChestTracker cache.")
                .apply(KEY_BARITONE);

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
                RESTOCK_CONTAINER_SOURCE, RESTOCK_SHULKER_MODE, RESTOCK_STACKS_PER_ITEM, RESTOCK_CONTAINER_MAX_DISTANCE,
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
