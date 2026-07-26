package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Analyzes Litematica's material list to determine which items need gathering.
 */
public class MaterialAnalyzer {

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

            // Force-update available counts from actual player inventory
            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            Class<?> utilsClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListUtils");
            utilsClass.getMethod("updateAvailableCounts", java.util.List.class,
                            net.minecraft.world.entity.player.Player.class)
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

            ctx.missingItems.sort((a, b) -> Integer.compare(b.neededCount, a.neededCount));

            if (ctx.missingItems.isEmpty()) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.all_materials_ready");
                tsm.setState(State.COMPLETED);
                return;
            }

            ctx.currentItemIndex = 0;
            tsm.setState(State.NEXT_ITEM);

        } catch (Exception e) {
            String msg = StringUtils.translate("playercontrolpp.message.baritone.analyze_error", e.getMessage());
            PlayerCompat.sendOverlayMessage(ctx.client.player, Component.nullToEmpty(msg));
            tsm.setState(State.STOPPED);
        }
    }

    private Set<String> buildGlobalIgnoreSet() {
        Set<String> set = new HashSet<>();
        if (!Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE.getBooleanValue()) return set;
        List<String> strings = Configs.BaritoneSettings.GLOBAL_IGNORE_LIST.getStrings();
        for (String s : strings) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    private boolean isInventoryFull(net.minecraft.client.Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
