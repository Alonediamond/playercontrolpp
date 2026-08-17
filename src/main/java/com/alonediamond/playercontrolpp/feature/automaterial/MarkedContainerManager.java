package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.config.Configs;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 自动续料建造的标记容器列表。
 *
 * <p>数据存在主配置的 {@code ConfigStringList}（{@code Restocks.MARKED_CONTAINERS}）里，
 * 一行一个，格式 {@code "维度 x y z [off]"}，例如 {@code "minecraft:overworld 10 64 -20"}。
 * 第五段写 {@code off} 表示这个容器被禁用（续料时不寻路过去）；四段的老数据一律视为启用，
 * 所以旧配置无需迁移。malilib 自带的列表编辑器可以直接改这些行。
 *
 * <p>维度 key 取自 {@link Level#dimension()} 的 {@code toString()}，写入与比较都走同一个
 * 规整函数，两边格式不会打架。
 */
public class MarkedContainerManager {

    /** 禁用标记：写在行尾，大小写不敏感。 */
    private static final String DISABLED_TOKEN = "off";

    private static final MarkedContainerManager INSTANCE = new MarkedContainerManager();

    private MarkedContainerManager() {}

    public static MarkedContainerManager getInstance() { return INSTANCE; }

    // ---- 对外接口 ----

    /** 全部标记项，含被禁用的。 */
    public List<MarkedContainer> all() {
        List<MarkedContainer> out = new ArrayList<>();
        for (String s : Configs.Restocks.MARKED_CONTAINERS.getStrings()) {
            MarkedContainer mc = parseEntry(s);
            if (mc != null) out.add(mc);
        }
        return out;
    }

    /**
     * 本轮续料真正要跑的容器，以及被过滤掉的数量（用于提示玩家为什么没去）。
     *
     * @param origin 距离过滤的基准点，一般是玩家当前位置
     */
    public Targets pickTargets(Level level, BlockPos origin) {
        String currentDim = dimensionIdOf(level);
        if (currentDim == null) return new Targets(Collections.emptyList(), 0, 0);

        int maxDistance = Configs.Restocks.RESTOCK_CONTAINER_MAX_DISTANCE.getIntegerValue();
        // 0 表示不限距离
        long maxDistanceSq = maxDistance <= 0 ? Long.MAX_VALUE : (long) maxDistance * maxDistance;

        List<BlockPos> usable = new ArrayList<>();
        int disabled = 0;
        int tooFar = 0;

        for (MarkedContainer mc : all()) {
            if (!mc.dimension().equals(currentDim)) continue;
            if (!mc.enabled()) { disabled++; continue; }
            if (mc.pos().distSqr(origin) > maxDistanceSq) { tooFar++; continue; }
            usable.add(mc.pos());
        }

        usable.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return new Targets(usable, disabled, tooFar);
    }

    /** 标记 {@code pos}。已经标记过则返回 {@code false}，不会重复添加。 */
    public boolean add(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;

        List<String> current = new ArrayList<>(Configs.Restocks.MARKED_CONTAINERS.getStrings());
        for (String s : current) {
            if (matches(s, pos, dim)) return false;
        }
        current.add(format(pos, dim, true));
        save(current);
        return true;
    }

    /** @return 该位置原本被标记过并已移除时为 {@code true}。 */
    public boolean remove(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        List<String> current = new ArrayList<>(Configs.Restocks.MARKED_CONTAINERS.getStrings());
        if (!current.removeIf(s -> matches(s, pos, dim))) return false;
        save(current);
        return true;
    }

    public boolean contains(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        for (String s : Configs.Restocks.MARKED_CONTAINERS.getStrings()) {
            if (matches(s, pos, dim)) return true;
        }
        return false;
    }

    /**
     * 切换该位置的启用状态。
     *
     * @return 切换后的状态；该位置没有被标记过时返回 {@code null}
     */
    public Boolean toggleEnabled(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return null;

        List<String> current = new ArrayList<>(Configs.Restocks.MARKED_CONTAINERS.getStrings());
        for (int i = 0; i < current.size(); i++) {
            MarkedContainer mc = parseEntry(current.get(i));
            if (mc == null || !mc.pos().equals(pos) || !mc.dimension().equals(dim)) continue;

            boolean enabled = !mc.enabled();
            current.set(i, format(pos, dim, enabled));
            save(current);
            return enabled;
        }
        return null;
    }

    public int size() { return Configs.Restocks.MARKED_CONTAINERS.getStrings().size(); }

    private static void save(List<String> entries) {
        Configs.Restocks.MARKED_CONTAINERS.setStrings(entries);
        Configs.saveToFile();
    }

    // ---- 解析与格式化 ----

    private static String format(BlockPos pos, String dim, boolean enabled) {
        String line = dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return enabled ? line : line + " " + DISABLED_TOKEN;
    }

    /** @return 解析结果；格式不对时返回 {@code null}。 */
    static MarkedContainer parseEntry(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 4 && parts.length != 5) return null;
        try {
            BlockPos pos = new BlockPos(
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            boolean enabled = parts.length == 4 || !DISABLED_TOKEN.equalsIgnoreCase(parts[4]);
            return new MarkedContainer(pos, parts[0], enabled);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean matches(String entry, BlockPos pos, String dim) {
        MarkedContainer mc = parseEntry(entry);
        return mc != null && mc.pos().equals(pos) && mc.dimension().equals(dim);
    }

    // ---- 维度 key（跨版本通用）----

    /**
     * 取维度 id，如 {@code "minecraft:overworld"}。
     *
     * <p>用 {@code dimension().identifier()}（老版本映射名 {@code location()}，预处理器会自动改名），
     * 不去解析 {@code ResourceKey.toString()} 的 {@code "ResourceKey[a / b]"} 形状——
     * 那个格式是调试输出，没有兼容性承诺。两种取法对原版维度得到的字符串相同，旧配置无需迁移。
     */
    static String dimensionIdOf(Level level) {
        try {
            return level.dimension().identifier().toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 数据类 ----

    public record MarkedContainer(BlockPos pos, String dimension, boolean enabled) {}

    /** 一次筛选的结果：可用位置（近到远）+ 因禁用/超距被跳过的数量。 */
    public record Targets(List<BlockPos> positions, int disabled, int tooFar) {
        public boolean isEmpty() { return positions.isEmpty(); }
        public int filtered() { return disabled + tooFar; }
    }
}
