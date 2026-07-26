package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.ContainerContentsCompat;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * Handles item transfer from containers: loose items first, then shulker boxes.
 */
public class ItemTransferExecutor {

    /**
     * Check whether enough of the current target item has been gathered,
     * counting items in the player's inventory AND inside shulker boxes.
     * Used after shulker box auto-store to decide whether to continue.
     */
    public boolean isCurrentItemSatisfied(GatherContext ctx) {
        if (ctx.currentTargetItem == null) return false;
        int total = countItemInInventory(ctx.currentTargetItem, ctx.client);
        total += countItemInShulkerBoxes(ctx.currentTargetItem, ctx.client);
        ctx.currentlyGathered = total;
        return total >= ctx.targetNeededTotal;
    }

    /**
     * Set up the next item to gather.
     */
    public void nextItem(GatherContext ctx, TaskStateMachine tsm) {
        ctx.justTookShulkerBox = false;
        ctx.totalBoxesTakenForItem = 0;
        ctx.totalStacksTakenForItem = 0;

        if (ctx.currentItemIndex >= ctx.missingItems.size()) {
            tsm.setState(State.COMPLETED);
            return;
        }

        MaterialItemEntry entry = ctx.missingItems.get(ctx.currentItemIndex);
        ctx.currentTargetItem = entry.item;
        ctx.targetNeededTotal = entry.neededCount;
        ctx.currentlyGathered = countItemInInventory(entry.item, ctx.client)
                + countItemInShulkerBoxes(entry.item, ctx.client);
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.foundPositions.clear();

        if (ctx.currentlyGathered >= ctx.targetNeededTotal) {
            ctx.currentItemIndex++;
            tsm.setState(State.NEXT_ITEM);
            return;
        }

        int stillNeeded = ctx.targetNeededTotal - ctx.currentlyGathered;
        ctx.currentTransferPlan = ItemTransferStrategy.calculate(stillNeeded, entry.maxStackSize);
        ctx.stacksTakenThisContainer.clear();
        ctx.shulkerBoxesTakenThisContainer.clear();

        tsm.setState(State.SEARCHING);
    }

    /**
     * Handle item transfer from an open container.
     */
    public void transfer(GatherContext ctx, TaskStateMachine tsm) {
        Minecraft mc = ctx.client;

        if (ctx.transferCooldown > 0) return;

        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            tsm.setState(State.VERIFYING);
            return;
        }

        if (isInventoryFull(mc)) {
            mc.player.closeContainer();
            tsm.onInventoryFull();
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;
        if (handler == null) {
            mc.player.closeContainer();
            tsm.setState(State.VERIFYING);
            return;
        }

        java.util.List<Slot> slots = handler.slots;

        // When the strategy says we need whole shulker boxes, try Phase 1b first
        // to avoid filling inventory with individual stacks.
        boolean boxesFirst = ctx.currentTransferPlan.shulkerBoxes > 0
                && ctx.totalBoxesTakenForItem < ctx.currentTransferPlan.shulkerBoxes;

        if (boxesFirst) {
            if (tryTransferShulkerBoxesFirst(mc, handler, slots, ctx)) return;
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
        } else {
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
            if (tryTransferShulkerBoxesFirst(mc, handler, slots, ctx)) return;
        }

        mc.player.closeContainer();
        ctx.transferCooldown = 8;
        tsm.setState(State.VERIFYING);
    }

    /**
     * Verify if enough items have been gathered for the current target.
     */
    public void verify(GatherContext ctx, TaskStateMachine tsm,
                        ContainerOpener opener, BaritonePathingController pathing) {
        ctx.currentlyGathered = countItemInInventory(ctx.currentTargetItem, ctx.client);
        ctx.currentlyGathered += countItemInShulkerBoxes(ctx.currentTargetItem, ctx.client);

        if (ctx.currentlyGathered >= ctx.targetNeededTotal) {
            ctx.currentItemIndex++;
            tsm.setState(State.NEXT_ITEM);
        } else {
            ctx.stacksTakenThisContainer.clear();
            ctx.shulkerBoxesTakenThisContainer.clear();

            ctx.currentPosIndex++;
            ctx.adjacentContainerTargets = null;
            ctx.adjacentTryIndex = 0;
            if (ctx.currentPosIndex >= ctx.foundPositions.size()) {
                ctx.stacksTakenThisContainer.clear();
                ctx.shulkerBoxesTakenThisContainer.clear();
                tsm.setState(State.SEARCHING);
            } else {
                navigateToContainer(ctx.foundPositions.get(ctx.currentPosIndex), ctx, tsm, opener, pathing);
            }
        }
    }

    private void navigateToContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                     ContainerOpener opener, BaritonePathingController pathing) {
        if (ctx.client.player == null) return;
        if (ctx.client.player.blockPosition().distSqr(pos) <= 25.0) {
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    // --- Helper methods ---

    /**
     * Phase 1b (maybe first): Transfer whole shulker boxes containing needed items.
     * Respects the global box limit from currentTransferPlan.
     */
    private boolean tryTransferShulkerBoxesFirst(Minecraft mc, AbstractContainerMenu handler,
                                                 java.util.List<Slot> slots, GatherContext ctx) {
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isShulkerBox(stack)) continue;
            if (!shulkerBoxContainsAnyMissingItem(stack, ctx)) continue;
            MaterialItemEntry bestEntry = findBestMissingItemForShulker(stack, ctx);
            if (bestEntry == null) continue;
            if (tryTransferShulker(mc, handler, slot, bestEntry, ctx)) {
                ctx.justTookShulkerBox = true;
                ctx.transferCooldown = 4;
                return true;
            }
        }
        return false;
    }

    /**
     * Phase 1a (maybe second): Transfer loose stacks of needed items.
     * Respects the global stack limit from currentTransferPlan.
     */
    private boolean tryTransferLooseItems(Minecraft mc, AbstractContainerMenu handler,
                                          java.util.List<Slot> slots, GatherContext ctx) {
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || isShulkerBox(stack)) continue;

            MaterialItemEntry matchedEntry = findMatchingMissingItem(stack, ctx);
            if (matchedEntry == null) continue;

            if (tryTransferLoose(mc, handler, slot, matchedEntry, ctx)) {
                ctx.transferCooldown = 4;
                return true;
            }
        }
        return false;
    }

    private MaterialItemEntry findMatchingMissingItem(ItemStack stack, GatherContext ctx) {
        for (MaterialItemEntry entry : ctx.missingItems) {
            if (itemsMatch(stack, entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private MaterialItemEntry findBestMissingItemForShulker(ItemStack shulkerBox, GatherContext ctx) {
        MaterialItemEntry best = null;
        int bestNeeded = 0;
        for (MaterialItemEntry entry : ctx.missingItems) {
            int have = countItemInInventory(entry.item, ctx.client) + countItemInShulkerBoxes(entry.item, ctx.client);
            int needed = entry.neededCount - have;
            if (needed > 128 && shulkerBoxContainsItem(shulkerBox, entry.item)) {
                if (needed > bestNeeded) {
                    bestNeeded = needed;
                    best = entry;
                }
            }
        }
        return best;
    }

    private boolean tryTransferLoose(Minecraft mc, AbstractContainerMenu handler, Slot slot, MaterialItemEntry entry, GatherContext ctx) {
        int have = countItemInInventory(entry.item, ctx.client);
        int needed = entry.neededCount - have;
        if (needed <= 0) return false;

        int taken = ctx.stacksTakenThisContainer.getOrDefault(entry.item, 0);
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int maxStacks = (needed + stackSize - 1) / stackSize;

        if (taken >= maxStacks) return false;

        try {
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            ctx.stacksTakenThisContainer.put(entry.item, taken + 1);
            ctx.totalStacksTakenForItem++;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryTransferShulker(Minecraft mc, AbstractContainerMenu handler, Slot slot, MaterialItemEntry entry, GatherContext ctx) {
        int have = countItemInInventory(entry.item, ctx.client) + countItemInShulkerBoxes(entry.item, ctx.client);
        int needed = entry.neededCount - have;
        if (needed <= 0) return false;

        // Respect global box limit from transfer plan
        int planBoxes = ctx.currentTransferPlan.shulkerBoxes;
        if (planBoxes > 0 && ctx.totalBoxesTakenForItem >= planBoxes) return false;

        int taken = ctx.shulkerBoxesTakenThisContainer.getOrDefault(entry.item, 0);
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int shulkerCap = 27 * stackSize;
        int maxBoxes = (needed + shulkerCap - 1) / shulkerCap;

        // Cap at remaining plan boxes
        if (planBoxes > 0) {
            int remainingPlan = planBoxes - ctx.totalBoxesTakenForItem;
            if (remainingPlan < maxBoxes) maxBoxes = remainingPlan;
        }

        if (taken >= maxBoxes) return false;

        try {
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            ctx.shulkerBoxesTakenThisContainer.put(entry.item, taken + 1);
            ctx.totalBoxesTakenForItem++;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int countItemInInventory(Item item, Minecraft mc) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (itemsMatch(stack, item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemInShulkerBoxes(Item targetItem, Minecraft mc) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isShulkerBox(stack)) {
                count += countItemInShulkerBox(stack, targetItem);
            }
        }
        return count;
    }

    private int countItemInShulkerBox(ItemStack shulkerBox, Item targetItem) {
        ItemContainerContents container = shulkerBox.getComponents().get(DataComponents.CONTAINER);
        if (container == null) return 0;
        int count = 0;
        for (ItemStack inner : ContainerContentsCompat.nonEmptyItems(container)) {
            if (itemsMatch(inner, targetItem)) {
                count += inner.getCount();
            }
        }
        return count;
    }

    private boolean isShulkerBox(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getPath().contains("shulker_box");
    }

    private boolean itemsMatch(ItemStack stack, Item targetItem) {
        if (stack.getItem() == targetItem) return true;
        Identifier stackId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Identifier targetId = BuiltInRegistries.ITEM.getKey(targetItem);
        return stackId.equals(targetId);
    }

    private boolean shulkerBoxContainsItem(ItemStack shulkerBox, Item targetItem) {
        ItemContainerContents container = shulkerBox.getComponents().get(DataComponents.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : ContainerContentsCompat.nonEmptyItems(container)) {
            if (itemsMatch(inner, targetItem)) return true;
        }
        return false;
    }

    private boolean shulkerBoxContainsAnyMissingItem(ItemStack shulkerBox, GatherContext ctx) {
        ItemContainerContents container = shulkerBox.getComponents().get(DataComponents.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : ContainerContentsCompat.nonEmptyItems(container)) {
            for (MaterialItemEntry entry : ctx.missingItems) {
                if (itemsMatch(inner, entry.item)) return true;
            }
        }
        return false;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
