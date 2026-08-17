package com.alonediamond.playercontrolpp.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过 {@code CONTAINER} 数据组件读潜影盒内容。
 *
 * <p>26.1 把 {@code ItemContainerContents.nonEmptyItems()} 的元素类型从 {@code ItemStack}
 * 换成了更轻的 {@code ItemStackTemplate}，要先 {@code create()} 才能查看。
 * 这个类藏掉差异，一律返回真正的 {@link ItemStack}。
 */
public final class ContainerContentsCompat {

    private ContainerContentsCompat() {}

    /** @return 容器组件里所有非空物品堆。返回的是副本，改它不影响原物品。 */
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
