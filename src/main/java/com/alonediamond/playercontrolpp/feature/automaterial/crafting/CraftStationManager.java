package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动合成用的工作站坐标（工作台 / 切石机），由玩家用热键在地图上标记。
 *
 * <p>不装 QuickShulker 时，合成器不再依赖"从背包里凭空打开工作站"，而是寻路到这些坐标。
 * 数据直接存在主配置的 {@code ConfigStringList} 里，一行一个，格式与标记容器一致：
 * {@code "维度 x y z"}，例如 {@code "minecraft:overworld 10 64 -20"}。
 *
 * <p>这里刻意<b>不</b>复用 {@link com.alonediamond.playercontrolpp.feature.automaterial.restock.MarkedContainerManager}：
 * 两者挂着不同的配置项与不同的热键，共用一份解析代码只会让"标记容器"和"标记工作站"互相污染。
 */
public final class CraftStationManager {

    /** 工作站种类。 */
    public enum Kind {
        CRAFTING_TABLE(Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE),
        STONECUTTER(Items.STONECUTTER, Blocks.STONECUTTER);

        public final Item item;
        public final Block block;

        Kind(Item item, Block block) {
            this.item = item;
            this.block = block;
        }

        private ConfigStringList config() {
            return this == CRAFTING_TABLE
                    ? Configs.BaritoneSettings.CRAFTING_TABLE_POSITIONS
                    : Configs.BaritoneSettings.STONECUTTER_POSITIONS;
        }

        /** 记录消息的翻译键前缀。 */
        private String messageKey() {
            return this == CRAFTING_TABLE ? "crafting_table" : "stonecutter";
        }
    }

    private static final CraftStationManager INSTANCE = new CraftStationManager();

    private CraftStationManager() {}

    public static CraftStationManager getInstance() { return INSTANCE; }

    // ---- 热键入口 ----

    /** 对着方块按热键：记录 / 取消记录。 */
    public void mark(Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_look_at_block");
            return;
        }

        BlockPos pos = hit.getBlockPos();
        String correctBlock = BuiltInRegistries.BLOCK.getKey(kind.block).toString();
        Block actual = mc.level.getBlockState(pos).getBlock();
        if (actual != kind.block) {
            // 记错了方块几乎总是玩家对着旁边的方块按了键；直接告诉他应该对着什么按。
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_wrong_block", correctBlock);
            return;
        }

        if (remove(pos, mc.level, kind)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_unmarked_" + kind.messageKey(),
                    pos.getX(), pos.getY(), pos.getZ());
        } else {
            add(pos, mc.level, kind);
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_marked_" + kind.messageKey(),
                    pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // ---- 查询 ----

    /**
     * @return 当前维度里距离 {@code origin} 最近的已记录坐标；没有时 {@code null}。
     *
     * <p>只按维度过滤，不按距离设上限：工作站是玩家自己标记的，走远一点也应当能用。
     */
    public BlockPos nearest(Level level, BlockPos origin, Kind kind) {
        String dim = dimensionIdOf(level);
        if (dim == null) return null;

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (String line : kind.config().getStrings()) {
            Entry entry = parse(line);
            if (entry == null || !entry.dimension().equals(dim)) continue;
            double distSq = entry.pos().distSqr(origin);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = entry.pos();
            }
        }
        return best;
    }

    /** @return 该维度是否记录过某种工作站。 */
    public boolean anyInDimension(Level level, Kind kind) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        for (String line : kind.config().getStrings()) {
            Entry entry = parse(line);
            if (entry != null && entry.dimension().equals(dim)) return true;
        }
        return false;
    }

    // ---- 增删 ----

    private boolean add(BlockPos pos, Level level, Kind kind) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        if (contains(pos, dim, kind)) return false;
        List<String> current = new ArrayList<>(kind.config().getStrings());
        current.add(dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        save(current, kind);
        return true;
    }

    private boolean remove(BlockPos pos, Level level, Kind kind) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        List<String> current = new ArrayList<>(kind.config().getStrings());
        boolean removed = current.removeIf(line -> matches(line, pos, dim));
        if (removed) save(current, kind);
        return removed;
    }

    private boolean contains(BlockPos pos, String dim, Kind kind) {
        for (String line : kind.config().getStrings()) {
            if (matches(line, pos, dim)) return true;
        }
        return false;
    }

    private static void save(List<String> entries, Kind kind) {
        kind.config().setStrings(entries);
        Configs.saveToFile();
    }

    // ---- 解析 ----

    private record Entry(BlockPos pos, String dimension) {}

    /** @return 解析结果；格式不对时 {@code null}。 */
    private static Entry parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            BlockPos pos = new BlockPos(
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            return new Entry(pos, parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean matches(String line, BlockPos pos, String dim) {
        Entry entry = parse(line);
        return entry != null && entry.pos().equals(pos) && entry.dimension().equals(dim);
    }

    /** 维度 id，写法与 {@code MarkedContainerManager} 相同。 */
    private static String dimensionIdOf(Level level) {
        try {
            return level.dimension().identifier().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
