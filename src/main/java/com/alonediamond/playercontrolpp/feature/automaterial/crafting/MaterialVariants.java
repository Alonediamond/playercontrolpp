package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.VariantMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 等价原材料的变体选择。
 *
 * <p>配方里的木板、原木、圆石这些材料通常是"任意一种都行"（游戏里用物品标签表达）。
 * LitematList 的配方树只能挑一种写进节点，于是会出现玩家箱子追踪里堆着白桦原木，
 * 而树坚持要橡木原木的情况。
 *
 * <p>这里给中间产物准备了几组等价类，并按玩家配置挑选：
 * <ul>
 *   <li>{@link VariantMode#CACHE_COUNT}：选箱子追踪缓存里数量最多的那种（默认，也是玩家要的贪心）；</li>
 *   <li>{@link VariantMode#TREE_DEFAULT}：不做替换，沿用配方树挑的那一种。</li>
 * </ul>
 *
 * <p><b>只对中间产物生效</b>：清单上真正要的物品（{@code GatherContext#isMissing}）永远不替换，
 * 否则"要橡木木板"会被悄悄换成白桦木板，建出来的东西就错了。
 */
final class MaterialVariants {

    private MaterialVariants() {}

    /**
     * 等价类：同一组里的物品在配方里可以互相替代。
     *
     * <p>键是组内任意一个成员，值是整组成员（含它自己）。查找时先按物品找组，找不到就没有替代。
     */
    private static final Map<Item, List<Item>> FAMILIES = families();

    /**
     * 为 {@code item} 挑一个等价变体。
     *
     * @param countInCache 某个物品在缓存里有多少个容器装着（数量越多越容易找到）
     * @return 选中的物品；不做替换或不适用时返回 {@code item} 本身
     */
    static Item choose(Item item, ToIntFunction<Item> countInCache) {
        if (item == null) return null;
        if (Configs.BaritoneSettings.RAW_MATERIAL_VARIANT_MODE.getOptionListValue() != VariantMode.CACHE_COUNT) {
            return item;
        }

        List<Item> family = FAMILIES.get(item);
        if (family == null || family.size() <= 1) return item;

        Item best = item;
        int bestCount = countInCache.applyAsInt(item);
        for (Item candidate : family) {
            if (candidate == item) continue;
            int count = countInCache.applyAsInt(candidate);
            // 严格大于才换：并列时保留配方树原本选的那一种，避免每次运行选到不同的木材。
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    /** @return {@code item} 是否有可替换的等价变体。 */
    static boolean hasFamily(Item item) {
        List<Item> family = item == null ? null : FAMILIES.get(item);
        return family != null && family.size() > 1;
    }

    /**
     * @return 整组等价变体的持有量之和。不在任何等价类里时就是它自己的持有量。
     *
     * <p>用于判定"中间产物够不够"：配方接受任意木板时，背包里各种木板都算数。
     */
    static int heldInFamily(Item item, ToIntFunction<Item> held) {
        List<Item> family = item == null ? null : FAMILIES.get(item);
        if (family == null || family.size() <= 1) return held.applyAsInt(item);
        int total = 0;
        for (Item member : family) total += held.applyAsInt(member);
        return total;
    }

    private static Map<Item, List<Item>> families() {
        Map<Item, List<Item>> map = new LinkedHashMap<>();

        List<Item> planks = new ArrayList<>();
        List<Item> logs = new ArrayList<>();
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo", "crimson", "warped")) {
            add(planks, "minecraft:" + wood + "_planks");
            // 菌柄（crimson/warped）没有 _log，只有 _stem；竹子的方块形态是 bamboo_block。
            if ("crimson".equals(wood) || "warped".equals(wood)) {
                add(logs, "minecraft:" + wood + "_stem");
            } else if ("bamboo".equals(wood)) {
                add(logs, "minecraft:bamboo_block");
            } else {
                add(logs, "minecraft:" + wood + "_log");
            }
        }
        register(map, planks);
        register(map, logs);

        // 圆石族：三者互替出现在很多配方里（熔炉、发射器、侦测器……）。
        List<Item> cobble = new ArrayList<>();
        add(cobble, "minecraft:cobblestone");
        add(cobble, "minecraft:blackstone");
        add(cobble, "minecraft:cobbled_deepslate");
        register(map, cobble);

        // 煤炭与木炭：火把类配方两者都行。
        List<Item> fuel = new ArrayList<>();
        fuel.add(Items.COAL);
        fuel.add(Items.CHARCOAL);
        register(map, fuel);

        return Map.copyOf(map);
    }

    /** 把一组成员登记进表：每个成员都能查到整组。 */
    private static void register(Map<Item, List<Item>> map, List<Item> members) {
        if (members.size() <= 1) return;
        List<Item> snapshot = List.copyOf(members);
        for (Item member : members) map.put(member, snapshot);
    }

    private static void add(List<Item> list, String id) {
        Item item = item(id);
        if (item != null) list.add(item);
    }

    private static Item item(String id) {
        try {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }
}
