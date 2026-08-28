package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.MaterialSource;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 读材料清单，算出还要收集什么，同时尊重清单方自己的忽略列表和本模组的全局忽略列表。
 *
 * <p>数据来源由 {@code materialListSource} 配置决定：跟随投影时读 Litematica 自己的材料清单
 * （要求其信息HUD开着；若清单已被 LitematList 接管则停机提示）；跟随 LitematList 时读它
 * 上传区域里处理过替换/忽略/聚合的清单，不要求投影HUD。
 *
 * <p>条目的 {@code neededCount} 一律写<b>需求总量</b>（投影的 countMissing / LitematList 的
 * totalCount），不是扣除持有量后的缺口。缺口只用来过滤「已经齐了」的条目；
 * 「还差多少」由运行中的 countEverywhere（含潜影盒内的持有量）对总量实时判定。
 * 两者口径必须一致：早先把「总量−持有量」当目标量、又拿持有总量去比满足，
 * 拿过一整盒后重启就会双重扣减——缺口 1152 被持有 1786 直接判满，误报「备货完毕」。
 */
public class MaterialAnalyzer {

    private static final String MATERIAL_LIST_UTILS = "fi.dy.masa.litematica.materials.MaterialListUtils";

    private final LitematicaIntegration litematica;
    private final LitematListIntegration litematlist;

    public MaterialAnalyzer(LitematicaIntegration litematica, LitematListIntegration litematlist) {
        this.litematica = litematica;
        this.litematlist = litematlist;
    }

    public void analyze(GatherContext ctx, TaskStateMachine tsm) {
        try {
            List<MaterialItemEntry> missing = readMissingMaterials(ctx, tsm);
            if (missing == null) return;

            ctx.missingItems.clear();
            ctx.missingItems.addAll(missing);

            // 需求量最大的排前面：这些最可能需要整盒搬。
            ctx.missingItems.sort((a, b) -> Integer.compare(b.neededCount, a.neededCount));

            if (ctx.missingItems.isEmpty()) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.all_materials_ready");
                tsm.setState(State.COMPLETED);
                return;
            }

            // 必须等缺失清单建好之后才检查：onInventoryFull() 是靠「身上有没有缺失清单里的材料」
            // 来决定要不要启动存盒的，而在这行之前清单还是空的——那会让存盒开个盒子、什么都不存、
            // 然后死循环。这个顺序还让「背包满但材料已齐」的情况在上面报 COMPLETED，
            // 而不是以「背包已满」停下。
            if (isInventoryFull(ctx.client)) {
                tsm.onInventoryFull();
                return;
            }

            ctx.currentItemIndex = 0;
            tsm.setState(State.NEXT_ITEM);

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Failed to analyse the material list", e);
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.analyze_error", String.valueOf(e));
            tsm.setState(State.STOPPED);
        }
    }

    /**
     * 按配置的数据来源读出所有还缺的材料。
     *
     * @return 缺失条目；{@code null} 表示读不到清单，已经报过原因并停机。
     */
    private List<MaterialItemEntry> readMissingMaterials(GatherContext ctx, TaskStateMachine tsm) throws Exception {
        if (Configs.BaritoneSettings.MATERIAL_LIST_SOURCE.getOptionListValue() == MaterialSource.LITEMATLIST) {
            return readFromLitematList(ctx, tsm);
        }
        return readFromLitematica(ctx, tsm);
    }

    /**
     * 跟随投影：读 Litematica 自己的材料清单。
     *
     * <p>LitematList 接管后 DataManager 里躺着的已是它的注入清单，投影自身的清单读不到，
     * 这条路与「跟随LitematList」也就无从区分——所以检测到接管时直接停机提示，
     * 让玩家在 LitematList 里取消接管，或把数据来源切换成跟随LitematList。
     */
    private List<MaterialItemEntry> readFromLitematica(GatherContext ctx, TaskStateMachine tsm) throws Exception {
        Object materialList = litematica.getMaterialList();
        if (materialList == null) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_material_list");
            tsm.setState(State.STOPPED);
            return null;
        }

        // DataManager 里的清单被换成了非投影包名的实现（LitematList 的注入清单等）：
        // 投影自身的清单已被接管、无从读取。继续跑只会拿着接管数据冒充「跟随投影」，
        // 所以按玩家「跟随投影」的意图直接停机提示。
        if (!materialList.getClass().getName().startsWith("fi.dy.masa.litematica.")) {
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.litematlist_took_over");
            tsm.setState(State.STOPPED);
            return null;
        }

        // 投影只在自己的 HUD 开着时才维护原生清单。
        Object hudRenderer = materialList.getClass().getMethod("getHudRenderer").invoke(materialList);
        boolean hudShowing = (Boolean) hudRenderer.getClass()
                .getMethod("getShouldRenderCustom").invoke(hudRenderer);
        if (!hudShowing) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_hud");
            tsm.setState(State.STOPPED);
            return null;
        }

        Set<String> globalIgnoreSet = buildGlobalIgnoreSet();
        Set<Object> litematicaIgnored = litematica.getIgnoredSet(materialList);

        // 对着真实物品栏重新数一遍；缓存里的计数可能是过期的。
        Object allMaterials = materialList.getClass()
                .getMethod("getMaterialsAll").invoke(materialList);
        Class.forName(MATERIAL_LIST_UTILS)
                .getMethod("updateAvailableCounts", List.class, Player.class)
                .invoke(null, allMaterials, ctx.client.player);

        List<MaterialItemEntry> missing = new ArrayList<>();
        for (Object entry : (List<?>) allMaterials) {
            if (litematicaIgnored.contains(entry)) continue;

            ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (globalIgnoreSet.contains(itemId)) continue;

            int countMissing = (Integer) entry.getClass().getMethod("getCountMissing").invoke(entry);
            int countAvailable = (Integer) entry.getClass().getMethod("getCountAvailable").invoke(entry);
            // 目标量写总量 countMissing；countAvailable 只用来判断这条还缺不缺。
            if (countAvailable < countMissing) {
                missing.add(new MaterialItemEntry(stack.getItem(), countMissing,
                        stack.getMaxStackSize()));
            }
        }
        return missing;
    }

    /**
     * 跟随 LitematList：读它上传区域的清单。API 返回前已经完成了替换、忽略与项目聚合，
     * 所以 LitematList 里被忽略的项目天然不会出现在结果里。
     */
    private List<MaterialItemEntry> readFromLitematList(GatherContext ctx, TaskStateMachine tsm) {
        if (!litematlist.isLoaded()) {
            // 配置还停在 LITEMATLIST 但模组不在（多半是装完配置后把它卸了）。
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.litematlist_not_loaded");
            tsm.setState(State.STOPPED);
            return null;
        }

        List<LitematListIntegration.MaterialEntry> uploaded = litematlist.getMaterialEntries();
        if (uploaded.isEmpty()) {
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.litematlist_no_list");
            tsm.setState(State.STOPPED);
            return null;
        }

        Set<String> globalIgnoreSet = buildGlobalIgnoreSet();
        List<MaterialItemEntry> missing = new ArrayList<>();
        for (LitematListIntegration.MaterialEntry entry : uploaded) {
            String itemId = BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString();
            if (globalIgnoreSet.contains(itemId)) continue;

            // missingCount 是 LitematList 按它自己的口径（含潜影盒内持有量）算出的缺口，
            // 这里只拿它过滤已齐的条目；目标量同样写总量 totalCount，口径与投影路径一致。
            if (entry.missingCount() > 0) {
                missing.add(new MaterialItemEntry(entry.stack().getItem(), entry.totalCount(),
                        entry.stack().getMaxStackSize()));
            }
        }
        return missing;
    }

    private Set<String> buildGlobalIgnoreSet() {
        Set<String> set = new HashSet<>();
        if (!Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE.getBooleanValue()) return set;
        for (String s : Configs.BaritoneSettings.GLOBAL_IGNORE_LIST.getStrings()) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
