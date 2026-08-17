package com.alonediamond.playercontrolpp.feature.automaterial;

import net.minecraft.world.item.Item;

/**
 * Litematica 材料清单里还需要收集的单个条目。
 */
public class MaterialItemEntry {
    public final Item item;
    public final int neededCount;
    public final int maxStackSize;

    public MaterialItemEntry(Item item, int neededCount, int maxStackSize) {
        this.item = item;
        this.neededCount = neededCount;
        this.maxStackSize = maxStackSize > 0 ? maxStackSize : 64;
    }

    public MaterialItemEntry(Item item, int neededCount) {
        this.item = item;
        this.neededCount = neededCount;
        this.maxStackSize = 64;
    }
}
