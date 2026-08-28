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
 * <p>数据来源由 {@code materialListSource} 配置决定：跟随投影时读 Litematica 的材料清单
 * （要求其信息HUD开着，即使 LitematList 已接管也照常读HUD）；跟随 LitematList 时读它
 * 上传区域里处理过替换/忽略/聚合的清单，不要求投影HUD。
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

            // 缺口最大的排前面：这些最可能需要整盒搬。
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
     * 跟随投影：读 Litematica 的材料清单。
     *
     * <p>就算 LitematList 接管了 DataManager 里的清单也走这条路——接管后 HUD 显示的就是
     * 接管后的内容，「跟随投影」的含义本来就是跟随HUD当前显示的清单。
     */
    private List<MaterialItemEntry> readFromLitematica(GatherContext ctx, TaskStateMachine tsm) throws Exception {
        Object materialList = litematica.getMaterialList();
        if (materialList == null) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_material_list");
            tsm.setState(State.STOPPED);
            return null;
        }

        // Litematica 只在自己的 HUD 开着时才维护这个清单。
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
            int needed = countMissing - countAvailable;
            if (needed > 0) {
                missing.add(new MaterialItemEntry(stack.getItem(), needed,
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

            // missingCount 与投影清单的「countMissing - countAvailable」同义：
            // 已按 LitematList 自己的口径（含潜影盒内的持有量）扣除。
            if (entry.missingCount() > 0) {
                missing.add(new MaterialItemEntry(entry.stack().getItem(), entry.missingCount(),
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
