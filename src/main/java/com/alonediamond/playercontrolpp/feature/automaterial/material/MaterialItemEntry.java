package com.alonediamond.playercontrolpp.feature.automaterial.material;

import net.minecraft.world.item.Item;

/**
 * 备货清单中的单种物品；neededCount 是需求总量，尚未扣除当前库存。
 */
public final class MaterialItemEntry {
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
