package com.alonediamond.playercontrolpp.feature.automaterial;

import net.minecraft.world.item.Item;

/**
 * Represents a single item entry from the Litematica material list
 * that still needs to be gathered.
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
}
