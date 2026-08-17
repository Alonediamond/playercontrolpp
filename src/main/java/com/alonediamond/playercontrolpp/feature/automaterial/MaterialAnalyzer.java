package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 读 Litematica 材料清单，算出还要收集什么，同时尊重 Litematica 自己的忽略列表和本模组的全局忽略列表。
 */
public class MaterialAnalyzer {

    private static final String MATERIAL_LIST_UTILS = "fi.dy.masa.litematica.materials.MaterialListUtils";

    private final LitematicaIntegration litematica;

    public MaterialAnalyzer(LitematicaIntegration litematica) {
        this.litematica = litematica;
    }

    public void analyze(GatherContext ctx, TaskStateMachine tsm) {
        try {
            Object materialList = litematica.getMaterialList();
            if (materialList == null) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_material_list");
                tsm.setState(State.STOPPED);
                return;
            }

            // Litematica 只在自己的 HUD 开着时才维护这个清单。
            Object hudRenderer = materialList.getClass().getMethod("getHudRenderer").invoke(materialList);
            boolean hudShowing = (Boolean) hudRenderer.getClass()
                    .getMethod("getShouldRenderCustom").invoke(hudRenderer);
            if (!hudShowing) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_hud");
                tsm.setState(State.STOPPED);
                return;
            }

            Set<String> globalIgnoreSet = buildGlobalIgnoreSet();
            Set<Object> litematicaIgnored = litematica.getIgnoredSet(materialList);

            // 对着真实物品栏重新数一遍；缓存里的计数可能是过期的。
            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            Class.forName(MATERIAL_LIST_UTILS)
                    .getMethod("updateAvailableCounts", List.class, Player.class)
                    .invoke(null, allMaterials, ctx.client.player);

            List<?> allList = (List<?>) allMaterials;
            ctx.missingItems.clear();
            for (Object entry : allList) {
                if (litematicaIgnored.contains(entry)) continue;

                ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (globalIgnoreSet.contains(itemId)) continue;

                int countMissing = (Integer) entry.getClass().getMethod("getCountMissing").invoke(entry);
                int countAvailable = (Integer) entry.getClass().getMethod("getCountAvailable").invoke(entry);
                int needed = countMissing - countAvailable;
                if (needed > 0) {
                    ctx.missingItems.add(new MaterialItemEntry(stack.getItem(), needed,
                            stack.getMaxStackSize()));
                }
            }

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
            Playercontrolpp.LOGGER.warn("Failed to analyse the Litematica material list", e);
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.analyze_error", String.valueOf(e));
            tsm.setState(State.STOPPED);
        }
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
