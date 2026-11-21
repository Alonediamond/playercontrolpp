package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Craft;
import net.minecraft.world.item.Item;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** 主线程的工作站指令。两种求解器传入已绑定格位和次数，逐层追溯传入本帧的配方、绝对目标及试投额度。 */
record WorkstationOrder(Item output, int target, String recipeType, Item stoneInput, int inputBudget,
                        Plan layout, IntSupplier outputCount, ExactPlacement exact) {
    record ExactPlacement(Craft craft, BooleanSupplier inputsAvailable, BooleanSupplier recipeCurrent,
                          BooleanSupplier reservesKept) {}

    static WorkstationOrder fixed(Plan layout, ExactPlacement placement, IntSupplier held) {
        Craft craft = placement.craft();
        int netYield = layout.outputCount() - craft.inputs().getOrDefault(craft.item(), 0);
        int target = Math.addExact(held.getAsInt(), Math.multiplyExact(netYield, craft.operations()));
        boolean cutting = layout.isExactStonecutting();
        Item input = cutting ? layout.ingredientsAt(0).getFirst().getItem() : null;
        return new WorkstationOrder(craft.item(), target, cutting ? "stonecutting" : null, input, craft.operations(),
                layout, held, placement);
    }
}
