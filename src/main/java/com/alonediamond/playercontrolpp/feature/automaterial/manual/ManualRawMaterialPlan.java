package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.function.ToIntFunction;

/** LitematList 临时树的自有快照，成品目标、原料目标及合成阶段预留互不覆盖。 */
public record ManualRawMaterialPlan(List<MaterialItemEntry> materials, boolean unsupportedRecipe, List<RawNode> roots) {
    public ManualRawMaterialPlan { materials = List.copyOf(materials); roots = List.copyOf(roots); }

    public RawNode rootFor(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return roots.stream().filter(root -> root.itemId().equals(id)).findFirst().orElse(null);
    }

    public static ManualRawMaterialPlan analyze(ManualMaterialBatch batch) {
        LitematListIntegration integration = LitematListIntegration.getInstance();
        if (!integration.isLoaded()) throw new IllegalStateException("litematlist_missing");
        List<LitematListIntegration.RawRequest> requests = new ArrayList<>();
        batch.amounts().forEach((id, count) -> requests.add(new LitematListIntegration.RawRequest(resolve(id), count)));
        int depth = Configs.BaritoneSettings.RAW_MATERIAL_MAX_DEPTH.getIntegerValue();
        if (depth < 0) depth = Math.max(0, LitematListIntegration.getObservedPlayerDepth());
        if (Configs.BaritoneSettings.RAW_MATERIAL_MAX_DEPTH.getIntegerValue() < 0
                && LitematListIntegration.getObservedPlayerDepth() < 0) depth = 5;
        List<RawNode> roots = integration.analyzeTemporaryMaterials(requests, depth);
        if (roots.size() != requests.size()) throw new IllegalStateException("raw_analysis_failed");
        return fromTrees(roots);
    }

    public static ManualRawMaterialPlan fromTrees(List<RawNode> roots) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        boolean unsupported = false;
        Deque<RawNode> remaining = new ArrayDeque<>(roots);
        while (!remaining.isEmpty()) {
            RawNode node = remaining.removeFirst();
            if (node.count() <= 0) throw new IllegalArgumentException("invalid_raw_count");
            if (node.children().isEmpty()) totals.merge(resolve(node.itemId()), node.count(), Math::addExact);
            else {
                unsupported |= !Set.of("crafting_shaped", "crafting_shapeless", "stonecutting").contains(node.recipeType());
                remaining.addAll(node.children());
            }
        }
        return new ManualRawMaterialPlan(entries(totals), unsupported, roots);
    }

    /** 只备原料时保护前批成果及既有成品，不把尚未生产的成品加入需求。 */
    public static Map<Item, Integer> reserves(ManualMaterialBatch batch, List<MaterialItemEntry> finalTargets,
                                             Map<Item, Integer> completed) {
        Map<Item, Integer> reserves = new LinkedHashMap<>(completed);
        for (MaterialItemEntry target : finalTargets) {
            String id = BuiltInRegistries.ITEM.getKey(target.item).toString();
            int baseline = Math.max(0, target.neededCount - batch.amount(id));
            reserves.merge(target.item, baseline, Math::max);
        }
        return Map.copyOf(reserves);
    }

    public List<MaterialItemEntry> rawOnlyTargets(Map<Item, Integer> reserves, ToIntFunction<Item> countHeld) {
        Map<Item, Integer> totals = new LinkedHashMap<>(reserves);
        for (MaterialItemEntry raw : materials) {
            int baseline = Math.max(reserves.getOrDefault(raw.item, 0), Math.max(0, countHeld.applyAsInt(raw.item)));
            totals.merge(raw.item, Math.addExact(baseline, raw.neededCount), Math::max);
        }
        return entries(totals);
    }

    private static List<MaterialItemEntry> entries(Map<Item, Integer> totals) {
        return totals.entrySet().stream().filter(entry -> entry.getValue() > 0).map(entry -> new MaterialItemEntry(entry.getKey(), entry.getValue(),
                entry.getKey().getDefaultMaxStackSize())).toList();
    }

    private static Item resolve(String id) {
        Item item = RegistryCompat.item(Identifier.parse(id));
        if (item == null || !BuiltInRegistries.ITEM.getKey(item).toString().equals(id))
            throw new IllegalArgumentException("unknown_item: " + id);
        return item;
    }
}
