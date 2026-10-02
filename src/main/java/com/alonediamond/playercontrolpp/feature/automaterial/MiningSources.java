package com.alonediamond.playercontrolpp.feature.automaterial;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「这个物品能靠挖什么方块得到」——原材料追溯的"整个世界内寻找"用它决定 {@code #mine} 的目标。
 *
 * <p>只认两种来源，其余一律当作挖不到（宁可跳过并提示，也不让 Baritone 去挖一堆挖不出东西的方块）：
 * <ol>
 *   <li><b>直接掉落表</b>：矿石 → 粗矿/宝石、黏土块 → 黏土球、雪块 → 雪球…… 这些物品本身不是方块，
 *       但正常挖掘对应方块就会掉出来，不需要熔炼；</li>
 *   <li><b>自掉落方块</b>：物品有方块形态，且正常挖掘掉落的就是它自己。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>处理的：需要熔炼才能得到的（铁锭/玻璃/平滑石……）、需要合成步骤的（木棍/书/桶……）、
 * 靠生物掉落的（皮革/线团……）。前者由调用方判为"无法获取"并跳过，与本模组的合成范围（工作台/切石机）一致。
 */
public final class MiningSources {

    private MiningSources() {}

    /** 物品 → 挖了能直接掉出它的方块。 */
    private static final Map<Item, List<String>> DIRECT_DROPS = directDrops();

    /**
     * 正常挖掘<b>不会</b>掉落自身的方块：即使物品有方块形态也不能靠 {@code #mine} 拿到。
     *
     * <p>只列常见且容易误判的那些——默认溯源白名单里真正会撞上的也就石头、玻璃、黏土、雪块、羊毛这几种，
     * 其余按"自掉落"处理；真挖错了也只是那一次拿不到，不会造成破坏性后果。
     */
    private static final Set<Block> NOT_SELF_DROPPING = notSelfDropping();

    /**
     * @param item 目标物品
     * @return 应该交给 Baritone 挖的方块注册名；没有已知来源时返回空列表
     */
    public static List<String> blocksFor(Item item) {
        if (item == null) return List.of();

        List<String> direct = DIRECT_DROPS.get(item);
        if (direct != null) return direct;

        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (!NOT_SELF_DROPPING.contains(block)) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null) {
                    return List.of(id.toString());
                }
            }
        }
        return List.of();
    }

    private static Map<Item, List<String>> directDrops() {
        Map<Item, List<String>> map = new LinkedHashMap<>();
        // 矿石 → 粗矿 / 宝石，正常挖掘直接掉落，不需要烧炼。
        map.put(Items.RAW_IRON, List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
        map.put(Items.RAW_GOLD, List.of(
                "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"));
        map.put(Items.RAW_COPPER, List.of(
                "minecraft:copper_ore", "minecraft:deepslate_copper_ore"));
        map.put(Items.COAL, List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"));
        map.put(Items.DIAMOND, List.of(
                "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));
        map.put(Items.EMERALD, List.of(
                "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"));
        map.put(Items.LAPIS_LAZULI, List.of(
                "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"));
        map.put(Items.REDSTONE, List.of(
                "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"));
        map.put(Items.QUARTZ, List.of("minecraft:nether_quartz_ore"));
        // 方块掉落非方块物品。
        map.put(Items.CLAY_BALL, List.of("minecraft:clay"));
        map.put(Items.SNOWBALL, List.of("minecraft:snow_block"));
        map.put(Items.AMETHYST_SHARD, List.of("minecraft:amethyst_cluster"));
        map.put(Items.GLOWSTONE_DUST, List.of("minecraft:glowstone"));
        map.put(Items.STRING, List.of("minecraft:cobweb"));
        map.put(Items.BONE_MEAL, List.of("minecraft:bone_block"));
        // 作物：Baritone 会走到成熟的作物前收割。
        map.put(Items.WHEAT, List.of("minecraft:wheat"));
        map.put(Items.CARROT, List.of("minecraft:carrots"));
        map.put(Items.POTATO, List.of("minecraft:potatoes"));
        map.put(Items.BEETROOT, List.of("minecraft:beetroots"));
        map.put(Items.NETHER_WART, List.of("minecraft:nether_wart"));
        return Map.copyOf(map);
    }

    private static Set<Block> notSelfDropping() {
        List<Block> blocks = new ArrayList<>(List.of(
                Blocks.STONE,                 // 掉圆石
                Blocks.GLASS, Blocks.GLASS_PANE,
                Blocks.CLAY,                  // 掉黏土球
                Blocks.SNOW_BLOCK,            // 掉雪球
                Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
                Blocks.DIRT_PATH, Blocks.FARMLAND,
                Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
                Blocks.MELON, Blocks.PUMPKIN, Blocks.BOOKSHELF,
                Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.AMETHYST_CLUSTER,
                Blocks.COBWEB, Blocks.SPAWNER, Blocks.BEE_NEST, Blocks.BEEHIVE
        ));
        // 羊毛没有剪刀就什么都不掉，16 种颜色逐一加进来。
        // 按注册名查而不是引用 Blocks.WHITE_WOOL 这类常量：26.x 把它们收进了 ColorCollection，
        // 逐个颜色的字段在新版本里已经不存在了，注册名则一直没变。
        for (DyeColor color : DyeColor.values()) {
            Block wool = BuiltInRegistries.BLOCK.getOptional(
                    Identifier.withDefaultNamespace(color.getSerializedName() + "_wool")).orElse(null);
            if (wool != null) blocks.add(wool);
        }
        return Set.copyOf(blocks);
    }
}
