package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Balance;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已抵交付的材料、还需交付的缺口与可用于配方的散装分别记账。 */
record WholeOrderStock(Balance balance, int missing) {
    static Map<Item, Integer> targets(List<MaterialItemEntry> entries) {
        Map<Item, Integer> targets = new LinkedHashMap<>();
        for (MaterialItemEntry entry : entries) targets.merge(entry.item, entry.neededCount, Math::max);
        return targets;
    }

    static WholeOrderStock allocate(Balance held, int target, int hardReserve) {
        int reserve = Math.max(hardReserve, Math.min(target, held.owned()));
        return new WholeOrderStock(new Balance(held.owned(), held.plain(), reserve), Math.max(0, target - held.owned()));
    }

    static boolean finishedCover(List<Goal> gaps, Map<String, Long> stock, Map<String, Integer> floors) {
        return gaps.stream().allMatch(goal -> goal.units() <= stock.getOrDefault(goal.item(), 0L) - floors.getOrDefault(goal.item(), 0));
    }

    static Map<Item, Integer> partialTargets(Map<String, Long> units, Map<String, Item> items, Map<Item, Balance> before) {
        Map<Item, Integer> targets = new LinkedHashMap<>();
        units.forEach((id, added) -> {
            Item item = items.get(id);
            targets.put(item, Math.toIntExact(before.get(item).owned() + added));
        });
        return targets;
    }

    /** 总下限包括已经为交付锁住的普通散装。期初不足下限时，保护现有库存，仍可用其他材料生产。 */
    static int additionalFloor(Balance balance, int requested, long modeledStock) {
        int alreadyKept = balance.plain() - balance.usable();
        return (int) Math.min(modeledStock, Math.max(0, requested - alreadyKept));
    }

    static Map<String, Integer> parseFloors(List<String> lines) {
        Map<String, Integer> floors = new LinkedHashMap<>();
        for (String row : lines) {
            if (row.isBlank()) continue;
            String[] fields = row.trim().split("\\s+");
            if (fields.length != 2) throw new IllegalArgumentException(row);
            Identifier id = Identifier.tryParse(fields[0]);
            if (id == null) throw new IllegalArgumentException(row);
            Item item = RegistryCompat.item(id);
            int count = Integer.parseInt(fields[1]);
            if (item == null || item == Items.AIR || count < 0) throw new IllegalArgumentException(row);
            floors.merge(id.toString(), count, Math::max);
        }
        return Map.copyOf(floors);
    }
}
