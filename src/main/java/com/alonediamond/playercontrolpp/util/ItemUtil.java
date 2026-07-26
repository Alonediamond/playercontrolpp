package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.ContainerContentsCompat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.Collections;
import java.util.List;

/**
 * Item predicates shared by the gathering, storage and water-fill features. Each of these was
 * previously copy-pasted into three or four classes.
 */
public final class ItemUtil {

    private ItemUtil() {}

    /**
     * @return whether this stack is a shulker box of any colour.
     *
     * <p>Checks the block type rather than matching {@code "shulker_box"} against the registry
     * path. Same answer for vanilla, no string allocation per call, and it does not depend on a
     * modded shulker box happening to be named conveniently.
     */
    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * @return whether this stack is the given item.
     *
     * <p>Plain identity: {@link Item}s are registry singletons, so two references are the same
     * item exactly when they are the same object. (The previous version compared registry keys as
     * a fallback, which cost two lookups per call in per-tick counting loops and would report two
     * <em>unregistered</em> items as equal, since both resolve to the default key.)
     */
    public static boolean is(ItemStack stack, Item item) {
        return stack.getItem() == item;
    }

    /**
     * @return the non-empty stacks stored inside a shulker box, or an empty list if it has no
     *         container component. Reflects the client's last-synced copy of the contents.
     */
    public static List<ItemStack> contentsOf(ItemStack shulkerBox) {
        ItemContainerContents contents = shulkerBox.get(DataComponents.CONTAINER);
        return contents == null ? Collections.emptyList() : ContainerContentsCompat.nonEmptyItems(contents);
    }

    /** @return how many of {@code item} are inside this shulker box. */
    public static int countInside(ItemStack shulkerBox, Item item) {
        int count = 0;
        for (ItemStack inner : contentsOf(shulkerBox)) {
            if (is(inner, item)) {
                count += inner.getCount();
            }
        }
        return count;
    }

    /** @return whether this shulker box holds at least one {@code item}. */
    public static boolean containsInside(ItemStack shulkerBox, Item item) {
        for (ItemStack inner : contentsOf(shulkerBox)) {
            if (is(inner, item)) return true;
        }
        return false;
    }
}
