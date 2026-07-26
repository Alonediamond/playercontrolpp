package com.alonediamond.playercontrolpp.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading the contents of a shulker box through the {@code CONTAINER} data component.
 *
 * <p>Minecraft 26.1 changed the element type of
 * {@code ItemContainerContents.nonEmptyItems()} from {@code ItemStack} to the new
 * lightweight {@code ItemStackTemplate}, which has to be materialised with
 * {@code create()} before it can be inspected. This class hides that difference and
 * always hands back real {@link ItemStack}s.
 */
public final class ContainerContentsCompat {

    private ContainerContentsCompat() {}

    /**
     * @return every non-empty stack stored inside the container component.
     *         The returned stacks are copies — mutating them does not affect the item.
     */
    public static List<ItemStack> nonEmptyItems(ItemContainerContents container) {
        List<ItemStack> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        //#if MC >= 260000
        for (net.minecraft.world.item.ItemStackTemplate template : container.nonEmptyItems()) {
            result.add(template.create());
        }
        //#else
        //$$ for (ItemStack stack : container.nonEmptyItems()) {
        //$$     result.add(stack);
        //$$ }
        //#endif
        return result;
    }
}
