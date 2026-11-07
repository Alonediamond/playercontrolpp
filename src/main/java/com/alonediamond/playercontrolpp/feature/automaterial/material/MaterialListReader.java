package com.alonediamond.playercontrolpp.feature.automaterial.material;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.MaterialSource;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** 读取清单快照；只处理来源、忽略项和需求总量，不驱动游戏操作。 */
public final class MaterialListReader {
    public record Result(List<MaterialItemEntry> items, String error) {}

    public Result read(Player player) {
        Map<Item, MaterialItemEntry> entries = new LinkedHashMap<>();
        Set<String> ignored = new HashSet<>();
        if (Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE.getBooleanValue()) {
            for (String id : Configs.BaritoneSettings.GLOBAL_IGNORE_LIST.getStrings()) ignored.add(id.trim());
        }
        if (Configs.BaritoneSettings.MATERIAL_LIST_SOURCE.getOptionListValue() == MaterialSource.LITEMATLIST) {
            var source = LitematListIntegration.getInstance();
            if (!source.isLoaded()) return new Result(List.of(), "litematlist_not_loaded");
            if (!source.isMaterialListAvailable()) return new Result(List.of(), "litematlist_no_list");
            for (var entry : source.getMaterialEntries()) add(entries, ignored, entry.stack(), entry.totalCount());
        } else {
            var source = LitematicaIntegration.getInstance();
            if (source.getMaterialList() == null) return new Result(List.of(), "no_material_list");
            if (!source.isMaterialListHudVisible()) return new Result(List.of(), "no_hud");
            // HUD 被其他模组接管时，也按玩家当前看到的清单读取。
            for (var entry : source.getMaterialListEntries(player)) {
                if (!entry.ignored()) add(entries, ignored, entry.stack(), entry.countMissing());
            }
        }
        return new Result(new ArrayList<>(entries.values()), null);
    }

    private void add(Map<Item, MaterialItemEntry> entries, Set<String> ignored, ItemStack stack, int total) {
        if (stack.isEmpty() || total <= 0 || ignored.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return;
        MaterialItemEntry old = entries.get(stack.getItem());
        int count = (int) Math.min(Integer.MAX_VALUE, (long) total + (old == null ? 0 : old.neededCount));
        entries.put(stack.getItem(), new MaterialItemEntry(stack.getItem(), count, stack.getMaxStackSize()));
    }
}
