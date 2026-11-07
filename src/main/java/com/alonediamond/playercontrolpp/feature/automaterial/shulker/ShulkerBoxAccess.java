package com.alonediamond.playercontrolpp.feature.automaterial.shulker;

import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.container.SlotTransfer;
import com.alonediamond.playercontrolpp.feature.automaterial.container.TransferSession;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.StorageMode;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** 潜影盒存取周期。借盒、打开、转移、回收均有独立状态和超时；归还由主流程处理。 */
public final class ShulkerBoxAccess {
    public enum StorageResult { ACTIVE, DONE, ABORTED, INVENTORY_FULL, FAILED }
    private enum Phase { TAKE, TAKE_WAIT, READY, FIND, PLACE, PLACE_WAIT, OPEN, OPEN_WAIT, TRANSFER, CLOSE, MINE, PICKUP }
    private Phase phase;
    private enum Operation { STORE, EXTRACT }
    private Operation operation;
    private boolean active;
    private boolean optionalStorage;
    private boolean useQuickShulker;
    private boolean anyItemsMoved;
    private boolean placed;
    private int phaseTicks, cooldownTicks, boxSlot, selectedSlot, sourceSlot;
    private BlockPos position, returnTarget;
    private ItemStack identity = ItemStack.EMPTY;
    private final Set<Integer> existingBoxes = new HashSet<>();
    private final Set<Integer> triedBoxes = new HashSet<>();
    private final TransferSession transfers = new TransferSession();
    private final Map<Item, Integer> targets = new HashMap<>();
    private StorageResult result = StorageResult.ACTIVE;

    public boolean isActive() { return active; }
    public static boolean isEnabled() { return Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER.getBooleanValue(); }
    public static boolean isQuickShulkerModeEnabled() {
        return Configs.BaritoneSettings.SHULKER_STORAGE_MODE.getOptionListValue() == StorageMode.QUICKSHULKER
                && QuickShulkerIntegration.getInstance().isLoaded();
    }

    public boolean startStorage(GatherContext ctx) { return startStorage(ctx, false); }
    public boolean startStorage(GatherContext ctx, boolean optionalStorage) {
        begin(ctx, Operation.STORE);
        this.optionalStorage = optionalStorage;
        phase = Phase.READY;
        return true;
    }

    public boolean startContainerBoxTake(GatherContext ctx, int menuSlot, BlockPos source) {
        begin(ctx, Operation.EXTRACT);
        sourceSlot = menuSlot;
        returnTarget = source;
        phase = Phase.TAKE;
        return true;
    }

    public boolean startExtraction(GatherContext ctx, int inventorySlot, BlockPos source, Map<Item, Integer> targets) {
        if (inventorySlot < 0 || inventorySlot >= Inventory.INVENTORY_SIZE) return false;
        begin(ctx, Operation.EXTRACT);
        boxSlot = inventorySlot;
        returnTarget = source;
        if (targets != null) this.targets.putAll(targets);
        phase = Phase.READY;
        return true;
    }

    private void begin(GatherContext ctx, Operation operation) {
        active = true;
        this.operation = operation;
        optionalStorage = false;
        anyItemsMoved = false;
        placed = false;
        useQuickShulker = isQuickShulkerModeEnabled();
        phaseTicks = cooldownTicks = 0;
        boxSlot = sourceSlot = -1;
        position = returnTarget = null;
        selectedSlot = InventoryCompat.getSelectedSlot(ctx.client.player.getInventory());
        result = StorageResult.ACTIVE;
        targets.clear();
        triedBoxes.clear();
        transfers.reset();
        existingBoxes.clear();
    }

    public StorageResult tick(GatherContext ctx) {
        if (!active) return result;
        Minecraft mc = ctx.client;
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) return finish(mc, StorageResult.FAILED);
        if (cooldownTicks > 0) { cooldownTicks--; return StorageResult.ACTIVE; }
        phaseTicks++;
        switch (phase) {
            case TAKE -> {
                Slot source = mc.player.containerMenu.getSlot(sourceSlot);
                if (!QuickShulkerIntegration.isOpenableShulkerBox(source.getItem())) return fail(ctx, "mixed_box_take_failed");
                identity = identityOf(source.getItem());
                rememberBoxes(mc);
                if (SlotTransfer.toPlayer(mc, source, 1) == 0) return fail(ctx, "mixed_box_take_failed");
                enter(Phase.TAKE_WAIT, 5);
            }
            case TAKE_WAIT -> {
                boxSlot = findNewBox(mc);
                if (boxSlot < 0) return fail(ctx, "mixed_box_take_failed");
                ctx.extractionBoxSlots.add(boxSlot);
                ctx.mixedBoxOriginSlot = sourceSlot;
                enter(Phase.READY, 0);
            }
            case READY -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                    if (phaseTicks > 40) return fail(ctx, "shulker_open_failed");
                    return StorageResult.ACTIVE;
                }
                if (operation == Operation.STORE) boxSlot = findStorageBox(ctx);
                if (boxSlot < 0) {
                    if (!anyItemsMoved && !optionalStorage) MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
                    return finish(mc, anyItemsMoved ? StorageResult.DONE : optionalStorage ? StorageResult.ABORTED : StorageResult.FAILED);
                }
                ItemStack box = mc.player.getInventory().getItem(boxSlot);
                if (!QuickShulkerIntegration.isOpenableShulkerBox(box)) return fail(ctx, "shulker_no_box");
                identity = identityOf(box);
                if ((operation == Operation.EXTRACT) && returnTarget == null && targets.isEmpty()) return fail(ctx, "mixed_box_empty");
                triedBoxes.add(boxSlot);
                transfers.reset();
                enter(useQuickShulker ? Phase.OPEN : Phase.FIND, 0);
            }
            case FIND -> {
                position = findPosition(mc);
                if (position == null) return fail(ctx, "shulker_no_position");
                if (boxSlot >= PlayerUtil.HOTBAR_SIZE) {
                    int menuSlot = PlayerUtil.menuSlotOf(mc.player.inventoryMenu, mc.player.getInventory(), boxSlot);
                    SlotActionCompat.swapWithHotbar(mc, mc.player.inventoryMenu.containerId, menuSlot, selectedSlot);
                    if (returnTarget != null) { ctx.extractionBoxSlots.remove(boxSlot); ctx.extractionBoxSlots.add(selectedSlot); }
                    boxSlot = selectedSlot;
                }
                InventoryCompat.setSelectedSlot(mc.player.getInventory(), boxSlot);
                enter(Phase.PLACE, 3);
            }
            case PLACE -> {
                if (!mc.level.getBlockState(position).isAir()) return fail(ctx, "shulker_place_failed");
                BlockPos support = position.below();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false));
                enter(Phase.PLACE_WAIT, 5);
            }
            case PLACE_WAIT -> {
                if (mc.level.getBlockState(position).getBlock() instanceof ShulkerBoxBlock
                        && !ItemUtil.isShulkerBox(mc.player.getInventory().getItem(boxSlot))) {
                    placed = true;
                    if (returnTarget != null) ctx.extractionBoxSlots.remove(boxSlot);
                    rememberBoxes(mc);
                    enter(Phase.OPEN, 2);
                } else if (phaseTicks > 30) return fail(ctx, "shulker_place_failed");
            }
            case OPEN -> {
                if (useQuickShulker) {
                    if (!QuickShulkerIntegration.getInstance().openShulkerBox(QuickShulkerIntegration.menuSlotForInventorySlot(boxSlot))) {
                        return fail(ctx, "shulker_open_failed");
                    }
                } else {
                    Direction face = ContainerOpener.nearestFace(mc.player.getEyePosition(), position);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(position).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5), face, position, false));
                }
                enter(Phase.OPEN_WAIT, 4);
            }
            case OPEN_WAIT -> {
                if (mc.player.containerMenu instanceof ShulkerBoxMenu) enter(Phase.TRANSFER, 2);
                else if (phaseTicks > 40) return fail(ctx, "shulker_open_failed");
            }
            case TRANSFER -> transferContents(ctx);
            case CLOSE -> {
                mc.player.closeContainer();
                if (useQuickShulker) return afterBox(ctx, boxSlot);
                for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
                    if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) {
                        InventoryCompat.setSelectedSlot(mc.player.getInventory(), i); break;
                    }
                }
                enter(Phase.MINE, 3);
            }
            case MINE -> {
                if (mc.level.getBlockState(position).isAir()) {
                    releaseKeys();
                    InventoryCompat.setSelectedSlot(mc.player.getInventory(), selectedSlot);
                    enter(Phase.PICKUP, 2);
                } else if (!(mc.level.getBlockState(position).getBlock() instanceof ShulkerBoxBlock)) {
                    return fail(ctx, "shulker_mine_failed");
                } else {
                    Direction face = ContainerOpener.nearestFace(mc.player.getEyePosition(), position);
                    if (phaseTicks == 1) mc.gameMode.startDestroyBlock(position, face);
                    mc.gameMode.continueDestroyBlock(position, face);
                    if (phaseTicks > 160) return fail(ctx, "shulker_mine_failed");
                }
            }
            case PICKUP -> {
                int recovered = findNewBox(mc);
                if (recovered >= 0) { placed = false; return afterBox(ctx, recovered); }
                if (phaseTicks > 100) return fail(ctx, "shulker_pickup_failed");
            }
        }
        return active ? StorageResult.ACTIVE : result;
    }

    /** 两种存取方向共享会话进展检查，每次只执行一次真实转移。 */
    private void transferContents(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu)) { fail(ctx, "shulker_open_failed"); return; }
        var menu = mc.player.containerMenu;
        if (!menu.getCarried().isEmpty()) { fail(ctx, "transfer_interrupted"); return; }
        // 模拟模式为回收盒子保留一个空格；先归还借盒，再交回满包整理流程。
        if ((operation == Operation.EXTRACT) && emptySlots(mc) <= (placed ? 1 : 0)) {
            ctx.storageAfterExtraction = true;
            enter(Phase.CLOSE, 0);
            return;
        }
        transfers.bind(menu);
        TransferSession.Result outcome = transfers.transferNext(menu.slots,
                slot -> canTransfer(ctx, slot), slot -> transferSlot(ctx, slot));
        if (outcome == TransferSession.Result.EXHAUSTED) {
            enter(Phase.CLOSE, 0);
        } else {
            if (outcome == TransferSession.Result.MOVED) anyItemsMoved = true;
            cooldownTicks = Configs.BaritoneSettings.CLICK_COOLDOWN.getIntegerValue();
        }
    }

    private boolean canTransfer(GatherContext ctx, Slot slot) {
        boolean playerSide = SlotTransfer.isPlayerSlot(ctx.client, slot);
        if ((operation == Operation.EXTRACT) == playerSide) return false;
        return (operation == Operation.EXTRACT) ? extractGap(ctx, slot.getItem().getItem()) > 0 : storable(ctx, slot.getItem());
    }

    private int transferSlot(GatherContext ctx, Slot slot) {
        Minecraft mc = ctx.client;
        if ((operation == Operation.EXTRACT)) return SlotTransfer.toPlayer(mc, slot, extractGap(ctx, slot.getItem().getItem()));
        int before = slot.getItem().getCount();
        SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId, slot.index);
        return Math.max(0, before - slot.getItem().getCount());
    }

    private StorageResult afterBox(GatherContext ctx, int slot) {
        InventoryCompat.setSelectedSlot(ctx.client.player.getInventory(), selectedSlot);
        if ((operation == Operation.EXTRACT)) {
            if (returnTarget != null) {
                ctx.extractionBoxSlots.add(slot);
                ctx.mixedBoxReturnSlot = slot;
                ctx.mixedBoxReturnTarget = returnTarget;
                ctx.mixedBoxIdentity = ctx.client.player.getInventory().getItem(slot).copy();
            }
            return finish(ctx.client, StorageResult.DONE);
        }
        triedBoxes.add(slot);
        if (!hasStorableMaterials(ctx)) return finish(ctx.client, StorageResult.DONE);
        enter(Phase.READY, 5);
        return StorageResult.ACTIVE;
    }

    private int extractGap(GatherContext ctx, Item item) {
        if (!targets.isEmpty()) return Math.max(0, targets.getOrDefault(item, 0) - ItemUtil.countLoose(ctx.client.player, item));
        for (MaterialItemEntry entry : ctx.wantedEntries()) if (entry.item == item) return Math.max(0, entry.neededCount - ctx.countHeld(item));
        return 0;
    }

    public boolean hasStorableMaterials(GatherContext ctx) { return countStorableMaterials(ctx) > 0; }

    public int countStorableMaterials(GatherContext ctx) {
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = ctx.client.player.getInventory().getItem(i);
            if (storable(ctx, stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean storable(GatherContext ctx, ItemStack stack) {
        return !stack.isEmpty() && !ItemUtil.isShulkerBox(stack)
                && (ctx.isWantedNow(stack.getItem()) || ctx.extraStorableItems.contains(stack.getItem()));
    }

    private int findStorageBox(GatherContext ctx) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (triedBoxes.contains(i) || ctx.extractionBoxSlots.contains(i)) continue;
            ItemStack stack = ctx.client.player.getInventory().getItem(i);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack) || ctx.isWantedNow(stack.getItem())) continue;
            List<ItemStack> contents = ItemUtil.contentsOf(stack);
            if (contents.size() < 27) return i;
            for (ItemStack inner : contents) if (inner.isEmpty() || inner.getCount() < inner.getMaxStackSize()) return i;
        }
        return -1;
    }

    private BlockPos findPosition(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = feet.relative(dir);
            if (mc.level.getBlockState(candidate).isAir() && mc.level.getBlockState(candidate.above()).isAir()
                    && mc.level.getBlockState(candidate.below()).isFaceSturdy(mc.level, candidate.below(), Direction.UP)
                    && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate)) <= PlayerUtil.blockReachSq(mc.player)) return candidate;
        }
        return null;
    }

    private void rememberBoxes(Minecraft mc) {
        existingBoxes.clear();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) existingBoxes.add(i);
    }

    private int findNewBox(Minecraft mc) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!existingBoxes.contains(i) && ItemUtil.isShulkerBox(stack) && ItemStack.isSameItemSameComponents(identityOf(stack), identity)) return i;
        }
        return -1;
    }

    private static ItemStack identityOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.CONTAINER);
        return copy;
    }

    private static int emptySlots(Minecraft mc) {
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        return count;
    }

    private void enter(Phase phase, int cooldownTicks) { this.phase = phase; this.cooldownTicks = cooldownTicks; phaseTicks = 0; }
    public void releaseKeys() { SimulatedInput.releaseAll(this); }
    public void cancel(Minecraft mc) {
        if (active) {
            reportPlacedBox(mc);
            finish(mc, StorageResult.FAILED);
        }
    }
    private void reportPlacedBox(Minecraft mc) {
        if (placed && position != null && mc != null && mc.player != null) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_recover_at", position.getX(), position.getY(), position.getZ());
        }
    }
    private StorageResult finish(Minecraft mc, StorageResult result) {
        releaseKeys(); active = false; this.result = result;
        if (mc != null && mc.player != null) InventoryCompat.setSelectedSlot(mc.player.getInventory(), selectedSlot);
        return result;
    }
    private StorageResult fail(GatherContext ctx, String key) {
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone." + key);
        reportPlacedBox(ctx.client);
        // 放置过盒子或已经借到盒子时停机，避免失败后遗忘世界中/背包中的盒子。
        return finish(ctx.client, placed || returnTarget != null ? StorageResult.FAILED : optionalStorage ? StorageResult.ABORTED : StorageResult.FAILED);
    }
}
