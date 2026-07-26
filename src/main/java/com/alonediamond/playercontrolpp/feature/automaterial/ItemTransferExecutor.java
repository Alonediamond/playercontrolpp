package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Moves items out of an open container: loose stacks and whole shulker boxes, both capped by the
 * plan {@link ItemTransferStrategy} produced for the current item.
 */
public class ItemTransferExecutor {

    /**
     * Minimum shortfall before taking a whole shulker box is worth it rather than loose stacks.
     * Two stacks: below that a box is almost certainly more than needed.
     */
    private static final int SHULKER_WORTH_IT_THRESHOLD = 128;
    /** Ticks between container clicks, to stay within the server's rate expectations. */
    private static final int CLICK_COOLDOWN = 4;
    /** Ticks to settle after closing a container before verifying what we got. */
    private static final int CLOSE_COOLDOWN = 8;

    /**
     * @return whether enough of the current item has been gathered, counting the inventory and
     *         the contents of any shulker boxes in it. Used after auto-store to decide whether to
     *         keep going.
     */
    public boolean isCurrentItemSatisfied(GatherContext ctx) {
        if (ctx.currentTargetItem == null) return false;
        ctx.currentlyGathered = countEverywhere(ctx.currentTargetItem, ctx.client);
        return ctx.currentlyGathered >= ctx.targetNeededTotal;
    }

    /** Move on to the next missing item, or finish. */
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
        ctx.currentlyGathered = countEverywhere(entry.item, ctx.client);
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

    /** Take what we can from the container that is currently open. */
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
        List<Slot> slots = handler.slots;

        // When the plan calls for whole boxes, take those first so loose stacks do not fill the
        // inventory before there is room for a box.
        boolean boxesFirst = ctx.currentTransferPlan.shulkerBoxes > 0
                && ctx.totalBoxesTakenForItem < ctx.currentTransferPlan.shulkerBoxes;

        if (boxesFirst) {
            if (tryTransferShulkerBoxes(mc, handler, slots, ctx)) return;
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
        } else {
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
            if (tryTransferShulkerBoxes(mc, handler, slots, ctx)) return;
        }

        mc.player.closeContainer();
        ctx.transferCooldown = CLOSE_COOLDOWN;
        tsm.setState(State.VERIFYING);
    }

    /** Recount after closing a container, then either advance or try the next location. */
    public void verify(GatherContext ctx, TaskStateMachine tsm,
                        ContainerOpener opener, BaritonePathingController pathing) {
        ctx.currentlyGathered = countEverywhere(ctx.currentTargetItem, ctx.client);

        if (ctx.currentlyGathered >= ctx.targetNeededTotal) {
            ctx.currentItemIndex++;
            tsm.setState(State.NEXT_ITEM);
            return;
        }

        ctx.stacksTakenThisContainer.clear();
        ctx.shulkerBoxesTakenThisContainer.clear();
        ctx.currentPosIndex++;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;

        if (ctx.currentPosIndex >= ctx.foundPositions.size()) {
            tsm.setState(State.SEARCHING);
        } else {
            navigateToContainer(ctx.foundPositions.get(ctx.currentPosIndex), ctx, tsm, opener, pathing);
        }
    }

    private void navigateToContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                     ContainerOpener opener, BaritonePathingController pathing) {
        if (ctx.client.player == null) return;
        if (ctx.client.player.blockPosition().distSqr(pos) <= PlayerUtil.blockReachSq(ctx.client.player)) {
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    // --- Transfer phases ---

    /** Take a whole shulker box holding something we need, within the plan's box budget. */
    private boolean tryTransferShulkerBoxes(Minecraft mc, AbstractContainerMenu handler,
                                            List<Slot> slots, GatherContext ctx) {
        for (Slot slot : slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtil.isShulkerBox(stack)) continue;

            MaterialItemEntry bestEntry = findBestMissingItemForShulker(stack, ctx);
            if (bestEntry == null) continue;

            if (tryTransferShulker(mc, handler, slot, bestEntry, ctx)) {
                ctx.justTookShulkerBox = true;
                ctx.transferCooldown = CLICK_COOLDOWN;
                return true;
            }
        }
        return false;
    }

    /** Take one loose stack of a needed item, within the plan's stack budget. */
    private boolean tryTransferLooseItems(Minecraft mc, AbstractContainerMenu handler,
                                          List<Slot> slots, GatherContext ctx) {
        for (Slot slot : slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || ItemUtil.isShulkerBox(stack)) continue;

            MaterialItemEntry matchedEntry = findMatchingMissingItem(stack, ctx);
            if (matchedEntry == null) continue;

            if (tryTransferLoose(mc, handler, slot, matchedEntry, ctx)) {
                ctx.transferCooldown = CLICK_COOLDOWN;
                return true;
            }
        }
        return false;
    }

    private MaterialItemEntry findMatchingMissingItem(ItemStack stack, GatherContext ctx) {
        for (MaterialItemEntry entry : ctx.missingItems) {
            if (ItemUtil.is(stack, entry.item)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * @return the missing item inside {@code shulkerBox} that we are shortest of, but only if the
     *         shortfall is big enough that taking a whole box is sensible.
     */
    private MaterialItemEntry findBestMissingItemForShulker(ItemStack shulkerBox, GatherContext ctx) {
        MaterialItemEntry best = null;
        int bestNeeded = 0;
        for (MaterialItemEntry entry : ctx.missingItems) {
            int needed = entry.neededCount - countEverywhere(entry.item, ctx.client);
            if (needed > SHULKER_WORTH_IT_THRESHOLD
                    && needed > bestNeeded
                    && ItemUtil.containsInside(shulkerBox, entry.item)) {
                bestNeeded = needed;
                best = entry;
            }
        }
        return best;
    }

    private boolean tryTransferLoose(Minecraft mc, AbstractContainerMenu handler, Slot slot,
                                     MaterialItemEntry entry, GatherContext ctx) {
        int needed = entry.neededCount - countInInventory(entry.item, ctx.client);
        if (needed <= 0) return false;

        int taken = ctx.stacksTakenThisContainer.getOrDefault(entry.item, 0);
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int maxStacks = ItemTransferStrategy.ceilDiv(needed, stackSize);
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

    private boolean tryTransferShulker(Minecraft mc, AbstractContainerMenu handler, Slot slot,
                                       MaterialItemEntry entry, GatherContext ctx) {
        int needed = entry.neededCount - countEverywhere(entry.item, ctx.client);
        if (needed <= 0) return false;

        int planBoxes = ctx.currentTransferPlan.shulkerBoxes;
        if (planBoxes > 0 && ctx.totalBoxesTakenForItem >= planBoxes) return false;

        int taken = ctx.shulkerBoxesTakenThisContainer.getOrDefault(entry.item, 0);
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int shulkerCap = ItemTransferStrategy.SHULKER_SLOT_COUNT * stackSize;
        int maxBoxes = ItemTransferStrategy.ceilDiv(needed, shulkerCap);

        if (planBoxes > 0) {
            maxBoxes = Math.min(maxBoxes, planBoxes - ctx.totalBoxesTakenForItem);
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

    // --- Counting ---

    /** @return how many of {@code item} the player holds, loose plus inside shulker boxes. */
    private int countEverywhere(Item item, Minecraft mc) {
        if (mc.player == null || item == null) return 0;
        int count = 0;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemUtil.is(stack, item)) {
                count += stack.getCount();
            } else if (ItemUtil.isShulkerBox(stack)) {
                count += ItemUtil.countInside(stack, item);
            }
        }
        return count;
    }

    /** @return how many of {@code item} sit loose in the inventory, not counting boxes. */
    private int countInInventory(Item item, Minecraft mc) {
        if (mc.player == null || item == null) return 0;
        int count = 0;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemUtil.is(stack, item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
