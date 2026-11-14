package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import com.alonediamond.playercontrolpp.config.Configs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这个物品能不能在世界里直接挖到，挖什么方块」——原材料追溯的世界来源判定。
 *
 * <p>这里的判定是<b>显式白名单</b>：只认下面表里写明的物品。早先的写法是
 * "有方块形态、且不在黑名单里就算能挖"，结果把 {@code oak_planks}、{@code smooth_stone}
 * 这类几乎不会自然存在的方块也判成可挖，Baritone 会去找根本不存在的东西，白白空转一分钟。
 *
 * <p>能挖到的物品只有两类：
 * <ol>
 *   <li><b>方块掉落非方块物品</b>：矿石 → 粗矿/宝石、黏土块 → 黏土球、雪块 → 雪球、石头 → 圆石；</li>
 *   <li><b>自掉落方块</b>：正常挖掘掉落的就是它自己（原木、圆石、沙子、泥土……）。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>处理的：需要熔炼才能得到的（铁锭/玻璃/平滑石……）、需要合成步骤的（木板/木棍/桶……）、
 * 靠生物掉落的（皮革/线团……）。它们由调用方判为"无法获取"并提示，而不是让玩家等一场空挖。
 */
final class MiningSources {

    private MiningSources() {}

    /** 物品 → 挖了能直接掉出它的方块（按优先级排列）。 */
    private static final Map<Item, List<String>> SOURCES = sources();
    private static final List<String> BLOCK_IDS = SOURCES.values().stream().flatMap(List::stream).distinct().toList();

    /**
     * @param item 目标物品
     * @return 应该交给 Baritone 挖的方块注册名；无法从世界直接获得时返回空列表
     */
    public static List<String> blocksFor(Item item) {
        if (item == null) return List.of();
        return SOURCES.getOrDefault(item, List.of());
    }

    /** @return 这个物品是不是至少有一种方块能在世界里直接挖到。 */
    public static boolean isNaturallyObtainable(Item item) {
        return !blocksFor(item).isEmpty();
    }

    /** 不推断维度生成规则；只在当前维度有限范围内确有方块证据时尝试。 */
    static List<String> availableBlocks(Minecraft mc, Item item) {
        if (mc.level == null || mc.player == null) return List.of();
        var known = survey().positions();
        int radius = Configs.BaritoneSettings.MINING_SEARCH_RADIUS.getIntegerValue();
        Map<String, Double> distances = new java.util.HashMap<>();
        for (String id : blocksFor(item)) {
            var block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
            double distance = known.getOrDefault(id, List.of()).stream()
                    .filter(pos -> !mc.level.hasChunkAt(pos) || block != null && mc.level.getBlockState(pos).is(block))
                    .mapToDouble(pos -> pos.distSqr(mc.player.blockPosition()))
                    .filter(value -> value <= (long) radius * radius).min().orElse(Double.POSITIVE_INFINITY);
            distances.put(id, distance);
        }
        return blocksFor(item).stream().filter(id -> Double.isFinite(distances.get(id)))
                .sorted(java.util.Comparator.comparingDouble(distances::get)).toList();
    }

    static KnownMiningSurvey.Status survey() {
        return KnownMiningSurvey.poll(allBlockIds(),
                Configs.BaritoneSettings.MINING_SEARCH_RADIUS.getIntegerValue());
    }

    static List<String> allBlockIds() { return BLOCK_IDS; }

    private static Map<Item, List<String>> sources() {
        Map<Item, List<String>> map = new LinkedHashMap<>();

        // ---- 矿石 → 粗矿 / 宝石（正常挖掘直接掉落，不需要烧炼）----
        map.put(Items.RAW_IRON, List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
        map.put(Items.RAW_GOLD, List.of(
                "minecraft:gold_ore", "minecraft:deepslate_gold_ore"));
        map.put(Items.RAW_COPPER, List.of(
                "minecraft:copper_ore", "minecraft:deepslate_copper_ore"));
        map.put(Items.COAL, List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"));
        map.put(Items.DIAMOND, List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));
        map.put(Items.EMERALD, List.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"));
        map.put(Items.LAPIS_LAZULI, List.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"));
        map.put(Items.REDSTONE, List.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"));
        map.put(Items.QUARTZ, List.of("minecraft:nether_quartz_ore"));

        // ---- 方块掉落非方块物品 ----
        map.put(Items.CLAY_BALL, List.of("minecraft:clay"));
        map.put(Items.SNOWBALL, List.of("minecraft:snow_block"));
        map.put(Items.AMETHYST_SHARD, List.of("minecraft:amethyst_cluster"));
        map.put(Items.GLOWSTONE_DUST, List.of("minecraft:glowstone"));
        map.put(Items.STRING, List.of("minecraft:cobweb"));
        map.put(Items.COBBLESTONE, List.of("minecraft:stone"));
        map.put(Items.COBBLED_DEEPSLATE, List.of("minecraft:deepslate"));
        map.put(Items.DIRT, List.of("minecraft:dirt", "minecraft:grass_block"));

        // ---- 自掉落方块 ----
        // 原木/菌柄：都是自掉落，且是同一种木材的全部等价来源（配方里木板可以换成任意木材）。
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo")) {
            String block = "bamboo".equals(wood) ? "minecraft:bamboo_block" : "minecraft:" + wood + "_log";
            Item item = item(block);
            if (item != null) map.put(item, single(block));
        }
        map.put(item("minecraft:crimson_stem"), single("minecraft:crimson_stem"));
        map.put(item("minecraft:warped_stem"), single("minecraft:warped_stem"));

        // 石头类建材：直接自掉落，是最常见的建筑原料。
        for (String id : List.of(
                "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
                "minecraft:blackstone", "minecraft:basalt", "minecraft:smooth_basalt",
                "minecraft:calcite", "minecraft:dripstone_block", "minecraft:netherrack",
                "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:magma_block",
                "minecraft:end_stone", "minecraft:purpur_block",
                "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
                "minecraft:sandstone", "minecraft:red_sandstone",
                "minecraft:moss_block", "minecraft:mud", "minecraft:packed_mud",
                "minecraft:obsidian", "minecraft:crying_obsidian",
                "minecraft:prismarine", "minecraft:dark_prismarine", "minecraft:sea_lantern",
                "minecraft:terracotta")) {
            Item item = item(id);
            if (item != null) map.put(item, single(id));
        }

        // ---- 作物：Baritone 会走到成熟的作物前收割 ----
        map.put(Items.WHEAT, List.of("minecraft:wheat"));
        map.put(Items.CARROT, List.of("minecraft:carrots"));
        map.put(Items.POTATO, List.of("minecraft:potatoes"));
        map.put(Items.BEETROOT, List.of("minecraft:beetroots"));
        map.put(Items.NETHER_WART, List.of("minecraft:nether_wart"));

        // 最后去掉解析失败（null 键）的条目，避免注册名在某个版本不存在时污染表。
        map.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue().isEmpty());
        return Map.copyOf(map);
    }

    private static Item item(String id) {
        try {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<String> single(String id) {
        List<String> list = new ArrayList<>(1);
        list.add(id);
        return List.copyOf(list);
    }
}
