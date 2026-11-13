package com.alonediamond.playercontrolpp.feature.automaterial.material;

import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 一轮备货的需求总量；已满足的条目仍保留，供合成计算预留库存。 */
public final class MaterialRequirements {
    private final List<MaterialItemEntry> entries = new ArrayList<>();
    private final List<MaterialItemEntry> view = Collections.unmodifiableList(entries);

    public void replaceWith(Collection<MaterialItemEntry> requirements) {
        entries.clear();
        entries.addAll(requirements);
        entries.sort(Comparator.comparingInt((MaterialItemEntry entry) -> entry.neededCount).reversed());
    }

    public void clear() {
        entries.clear();
    }

    public List<MaterialItemEntry> entries() {
        return view;
    }

    public MaterialItemEntry entryFor(Item item) {
        if (item == null) return null;
        for (MaterialItemEntry entry : entries) {
            if (entry.item == item) return entry;
        }
        return null;
    }

    public boolean contains(Item item) {
        return entryFor(item) != null;
    }

    public int targetFor(Item item) {
        MaterialItemEntry entry = entryFor(item);
        return entry == null ? 0 : entry.neededCount;
    }

    /**
     * 追溯子材料也参与本次取物，但不修改清单预留量。
     * 同种材料同时出现在清单和追溯里时，采用较大的目标总量。
     */
    public List<MaterialItemEntry> includingTarget(Item item, int targetTotal) {
        if (item == null) return view;
        MaterialItemEntry existing = entryFor(item);
        if (existing != null && targetTotal <= existing.neededCount) return view;

        List<MaterialItemEntry> wanted = new ArrayList<>(entries);
        wanted.removeIf(entry -> entry.item == item);
        wanted.add(new MaterialItemEntry(item, targetTotal, item.getDefaultMaxStackSize()));
        return wanted;
    }
}
