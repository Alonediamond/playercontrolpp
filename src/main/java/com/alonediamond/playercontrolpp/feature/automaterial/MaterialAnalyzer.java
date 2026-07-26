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
 * Reads Litematica's material list and works out what still has to be gathered, honouring both
 * Litematica's own ignore list and this mod's global ignore list.
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

            // Litematica only keeps the list up to date while its HUD is on.
            Object hudRenderer = materialList.getClass().getMethod("getHudRenderer").invoke(materialList);
            boolean hudShowing = (Boolean) hudRenderer.getClass()
                    .getMethod("getShouldRenderCustom").invoke(hudRenderer);
            if (!hudShowing) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_hud");
                tsm.setState(State.STOPPED);
                return;
            }

            if (isInventoryFull(ctx.client)) {
                tsm.onInventoryFull();
                return;
            }

            Set<String> globalIgnoreSet = buildGlobalIgnoreSet();
            Set<Object> litematicaIgnored = litematica.getIgnoredSet(materialList);

            // Recount against the real inventory; the cached counts can be stale.
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

            // Biggest shortfall first: the items most likely to need whole shulker boxes.
            ctx.missingItems.sort((a, b) -> Integer.compare(b.neededCount, a.neededCount));

            if (ctx.missingItems.isEmpty()) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.all_materials_ready");
                tsm.setState(State.COMPLETED);
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
