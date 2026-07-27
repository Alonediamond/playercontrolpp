package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.StorageMode;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Frees inventory space during auto-gathering by moving already-collected building materials into
 * a shulker box.
 *
 * <pre>
 * FINDING_SHULKER -&gt; FINDING_POSITION -&gt; SWITCHING_SHULKER -&gt; PLACING
 *                 -&gt; OPENING -&gt; TRANSFERRING -&gt; CLOSING -&gt; MINING -&gt; WAITING_PICKUP -&gt; DONE
 * </pre>
 *
 * <p>With QuickShulker installed and selected in the config the middle of that is skipped: the box
 * is opened in place (FINDING_SHULKER -&gt; QUICK_OPEN -&gt; TRANSFERRING -&gt; CLOSING -&gt; DONE), which
 * avoids placing and mining a block altogether.
 */
public class ShulkerBoxStorage {

    public enum StorageState {
        IDLE, FINDING_SHULKER, FINDING_POSITION, SWITCHING_SHULKER,
        PLACING, OPENING, QUICK_OPEN, TRANSFERRING, CLOSING,
        MINING, WAITING_PICKUP, DONE
    }

    public enum StorageResult {
        ACTIVE, DONE, FAILED
    }

    /** Slots in a shulker box's own container screen: indices 0..26. */
    private static final int BOX_SLOT_COUNT = ItemTransferStrategy.SHULKER_SLOT_COUNT;
    /** First player-inventory slot in a shulker box screen (27 box slots come first). */
    private static final int BOX_SCREEN_PLAYER_START = BOX_SLOT_COUNT;
    /** Last player-inventory slot in a shulker box screen. */
    private static final int BOX_SCREEN_PLAYER_END = BOX_SLOT_COUNT + Inventory.INVENTORY_SIZE - 1;
    /** Safety cap on quick-move clicks in one storage cycle. */
    private static final int MAX_TRANSFERS_PER_CYCLE = 200;

    /** Retries when looking for somewhere to put the box down. */
    private static final int MAX_POSITION_RETRIES = 3;
    /** Retries when QuickShulker's open packet does not take. */
    private static final int MAX_QUICK_OPEN_RETRIES = 5;
    /** Ticks after clicking before checking whether the box really got placed. */
    private static final int PLACE_VERIFY_TICKS = 4;
    /** Total attempts at placing before failing. */
    private static final int MAX_PLACE_ATTEMPTS = 10;
    /** Ticks to wait for the box's screen to appear. */
    private static final int OPEN_VERIFY_LIMIT = 30;
    /** Ticks to keep mining before assuming something is wrong. */
    private static final int MAX_MINING_TICKS = 100;
    /** Ticks to wait for the mined box to be picked up. */
    private static final int MAX_PICKUP_WAIT_TICKS = 100;

    private StorageState state = StorageState.IDLE;
    private boolean active;
    /** Set once a terminal outcome is reached, so tick() can report it exactly once. */
    private StorageResult terminalResult = StorageResult.ACTIVE;
    private int cooldown;
    private int retryCount;
    private int miningTicks;
    private int waitTicks;
    private int openVerifyTicks;

    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();
    private boolean useQuickShulkerMode;
    private boolean anyItemsTransferred;
    /** Inventory slots whose box turned out to be full; remembered across cycles. */
    private final java.util.Set<Integer> knownFullSlots = new java.util.HashSet<>();

    private int shulkerSlotIndex = -1;
    private BlockPos placedPos;           // where the box was placed
    private BlockPos placeAgainst;        // the block clicked against to place it
    private Direction placeClickFace;     // which face of placeAgainst was clicked
    private int transferIndex;
    private int prevSelectedSlot;

    public boolean isActive() { return active; }

    public static boolean isEnabled() {
        return Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER.getBooleanValue();
    }

    /** @return whether QuickShulker mode applies: selected in the config <em>and</em> installed. */
    public static boolean isQuickShulkerModeEnabled() {
        StorageMode mode = (StorageMode) Configs.BaritoneSettings.SHULKER_STORAGE_MODE.getOptionListValue();
        return mode == StorageMode.QUICKSHULKER
                && QuickShulkerIntegration.getInstance().isLoaded();
    }

    public boolean startStorage(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (mc.player == null) return false;

        state = StorageState.IDLE;
        active = true;
        terminalResult = StorageResult.ACTIVE;
        cooldown = 0;
        retryCount = 0;
        miningTicks = 0;
        waitTicks = 0;
        openVerifyTicks = 0;
        shulkerSlotIndex = -1;
        placedPos = null;
        placeAgainst = null;
        placeClickFace = null;
        transferIndex = 0;
        prevSelectedSlot = InventoryCompat.getSelectedSlot(mc.player.getInventory());
        anyItemsTransferred = false;
        // knownFullSlots is deliberately kept: a box that was full last cycle is still full.
        useQuickShulkerMode = isQuickShulkerModeEnabled();

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_start");
        return true;
    }

    public StorageResult tick(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (!active || mc.player == null) {
            return terminalResult != StorageResult.ACTIVE ? terminalResult : StorageResult.ACTIVE;
        }
        if (cooldown > 0) { cooldown--; return StorageResult.ACTIVE; }
        if (mc.player.isDeadOrDying()) { abort(mc); return StorageResult.FAILED; }

        switch (state) {
            case IDLE -> state = StorageState.FINDING_SHULKER;
            case FINDING_SHULKER -> doFindShulker(mc, ctx);
            case FINDING_POSITION -> doFindPosition(mc);
            case SWITCHING_SHULKER -> doSwitchToShulker(mc);
            case PLACING -> doPlace(mc);
            case OPENING -> doOpen(mc);
            case QUICK_OPEN -> doQuickOpen(mc);
            case TRANSFERRING -> doTransfer(mc, ctx);
            case CLOSING -> doClose(mc);
            case MINING -> doMine(mc);
            case WAITING_PICKUP -> { return doWaitPickup(mc); }
            case DONE -> {
                active = false;
                return StorageResult.DONE;
            }
        }

        if (!active && terminalResult != StorageResult.ACTIVE) {
            return terminalResult;
        }
        return StorageResult.ACTIVE;
    }

    // ---- Phases ----

    private void doFindShulker(Minecraft mc, GatherContext ctx) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (knownFullSlots.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!ItemUtil.isShulkerBox(stack)) continue;
            // Never store into a box we are supposed to be collecting.
            if (isOnMissingList(stack, ctx)) continue;
            // Fullness is judged from the open screen, not the item's NBT, which can be stale.
            shulkerSlotIndex = i;
            state = useQuickShulkerMode ? StorageState.QUICK_OPEN : StorageState.FINDING_POSITION;
            return;
        }
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
        terminalResult = StorageResult.FAILED;
        active = false;
    }

    /**
     * QuickShulker mode: open the box where it sits. No swapping — just translate the inventory
     * index into a player-screen slot index and send QuickShulker's own packet.
     */
    private void doQuickOpen(Minecraft mc) {
        if (!quickShulker.isLoaded()) {
            fail(mc);
            return;
        }

        int screenSlot = playerScreenSlot(shulkerSlotIndex);
        if (!quickShulker.openShulkerBox(screenSlot)) {
            retryCount++;
            if (retryCount < MAX_QUICK_OPEN_RETRIES) {
                cooldown = 3;
                return;
            }
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            fail(mc);
            return;
        }

        retryCount = 0;
        openVerifyTicks = 0;
        transferIndex = 0;
        state = StorageState.TRANSFERRING;
    }

    /**
     * Find somewhere at the player's own feet level to put the box, in front first, then behind
     * and to the sides. Never on the block the player is standing on.
     */
    private void doFindPosition(Minecraft mc) {
        BlockPos playerFeet = mc.player.blockPosition();

        float yaw = mc.player.getYRot();
        double rad = Math.toRadians(yaw);
        int facingX = (int) -Math.round(Math.sin(rad));
        int facingZ = (int) Math.round(Math.cos(rad));
        if (facingX == 0 && facingZ == 0) { facingZ = 1; }

        int[][] offsets = {
            {facingX, facingZ},
            {facingX * 2, facingZ * 2},
            {-facingX, -facingZ},        // behind
            {facingZ, -facingX},         // right
            {-facingZ, facingX},         // left
        };

        double reachSq = PlayerUtil.blockReachSq(mc.player);

        for (int[] off : offsets) {
            int ox = off[0], oz = off[1];
            BlockPos ground = playerFeet.offset(ox, -1, oz);
            BlockPos placeAt = playerFeet.offset(ox, 0, oz);

            if (placeAt.equals(playerFeet)) continue;

            BlockState groundState = mc.level.getBlockState(ground);
            BlockState placeState = mc.level.getBlockState(placeAt);

            // isFaceSturdy is the non-deprecated way to ask "can something stand on top of this";
            // the old isSolid() was Mojang's legacy approximation and is cached less well.
            if (!groundState.isFaceSturdy(mc.level, ground, Direction.UP)) continue;
            if (!placeState.isAir() && !placeState.canBeReplaced()) continue;
            if (playerFeet.distSqr(placeAt) > reachSq) continue;

            placedPos = placeAt;
            placeAgainst = ground;
            placeClickFace = Direction.UP;
            retryCount = 0;
            state = StorageState.SWITCHING_SHULKER;
            return;
        }

        retryCount++;
        if (retryCount >= MAX_POSITION_RETRIES) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_position");
            fail(mc);
            return;
        }
        // Look down and give the ground a moment to load, then try again.
        mc.player.setXRot(90f);
        cooldown = 10;
    }

    private void doSwitchToShulker(Minecraft mc) {
        if (shulkerSlotIndex < PlayerUtil.HOTBAR_SIZE) {
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), shulkerSlotIndex);
        } else {
            // Swap the box down into whichever hotbar slot is selected, via three clicks.
            int hotbarSlot = InventoryCompat.getSelectedSlot(mc.player.getInventory());
            int containerId = mc.player.containerMenu.containerId;
            int hotbarScreenSlot = InventoryMenu.USE_ROW_SLOT_START + hotbarSlot;
            SlotActionCompat.pickup(mc, containerId, hotbarScreenSlot);
            SlotActionCompat.pickup(mc, containerId, shulkerSlotIndex);
            SlotActionCompat.pickup(mc, containerId, hotbarScreenSlot);
        }
        cooldown = 3;
        state = StorageState.PLACING;
    }

    private void doPlace(Minecraft mc) {
        if (placedPos == null || placeAgainst == null) { fail(mc); return; }

        if (retryCount == 0) {
            faceToward(mc, Vec3.atCenterOf(placedPos));

            Vec3 hitPos = new Vec3(
                    placeAgainst.getX() + 0.5,
                    placeAgainst.getY() + 1.0,
                    placeAgainst.getZ() + 0.5
            );
            BlockHitResult hitResult = new BlockHitResult(hitPos, placeClickFace, placeAgainst, false);

            try {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            } catch (Exception e) {
                // Fall back to vanilla's own raycast; released by releaseKeys() when we stop.
                SimulatedInput.hold(mc.options.keyUse, this);
                cooldown = 2;
                return;
            }
        }

        retryCount++;
        if (retryCount < PLACE_VERIFY_TICKS) {
            cooldown = 2;
            return;
        }

        // Placed successfully if the target is no longer air.
        if (mc.level.getBlockState(placedPos).isAir()) {
            if (retryCount < MAX_PLACE_ATTEMPTS) {
                cooldown = 3;
                return;
            }
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_place_failed");
            fail(mc);
            return;
        }

        SimulatedInput.release(mc.options.keyUse, this);
        retryCount = 0;
        cooldown = 3;
        state = StorageState.OPENING;
    }

    private void doOpen(Minecraft mc) {
        if (placedPos == null) { fail(mc); return; }

        faceToward(mc, Vec3.atCenterOf(placedPos));

        Direction nearestFace = getNearestFace(mc, placedPos);
        Vec3 hitPos = new Vec3(
                placedPos.getX() + 0.5 + nearestFace.getStepX() * 0.5,
                placedPos.getY() + 0.5 + nearestFace.getStepY() * 0.5,
                placedPos.getZ() + 0.5 + nearestFace.getStepZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitPos, nearestFace, placedPos, false);

        try {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        } catch (Exception e) {
            SimulatedInput.hold(mc.options.keyUse, this);
            cooldown = 2;
            return;
        }

        cooldown = 6;
        state = StorageState.TRANSFERRING;
        retryCount = 0;
        openVerifyTicks = 0;
        transferIndex = 0;
    }

    private void doTransfer(Minecraft mc, GatherContext ctx) {
        if (placedPos != null) {
            faceToward(mc, Vec3.atCenterOf(placedPos));
        }

        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            openVerifyTicks++;
            if (openVerifyTicks > OPEN_VERIFY_LIMIT) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                fail(mc);
            }
            return;
        }
        openVerifyTicks = 0;
        SimulatedInput.release(mc.options.keyUse, this);

        AbstractContainerMenu handler = mc.player.containerMenu;

        if (isShulkerBoxFull()) {
            mc.player.closeContainer();
            knownFullSlots.add(shulkerSlotIndex);
            if (!anyItemsTransferred) {
                // Nothing fit here; only loop back if another box is worth trying.
                if (!hasCandidateShulker(mc, ctx)) {
                    MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
                    fail(mc);
                    return;
                }
                cooldown = 3;
                state = StorageState.FINDING_SHULKER;
                return;
            }
            state = StorageState.CLOSING;
            return;
        }

        // Move one matching stack per tick from the player half of the screen into the box.
        for (int i = BOX_SCREEN_PLAYER_START;
             i <= BOX_SCREEN_PLAYER_END && transferIndex < MAX_TRANSFERS_PER_CYCLE; i++) {
            transferIndex++;
            Slot slot = handler.getSlot(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!isOnMissingList(stack, ctx)) continue;

            try {
                SlotActionCompat.quickMove(mc, handler.containerId, i);
                anyItemsTransferred = true;
                cooldown = 2;
                return;
            } catch (Exception e) {
                // This slot refused; try the next one.
            }
        }

        // A box with no empty slot left cannot accept a new item type either.
        if (!hasEmptySlotInShulkerBox()) {
            knownFullSlots.add(shulkerSlotIndex);
        }
        state = StorageState.CLOSING;
        cooldown = 3;
    }

    private void doClose(Minecraft mc) {
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }

        if (useQuickShulkerMode) {
            // Nothing was placed, so there is nothing to mine or pick up.
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
            terminalResult = StorageResult.DONE;
            state = StorageState.DONE;
            return;
        }

        cooldown = 3;
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.PICKAXES)) {
                InventoryCompat.setSelectedSlot(mc.player.getInventory(), i);
                break;
            }
        }
        miningTicks = 0;
        state = StorageState.MINING;
    }

    private void doMine(Minecraft mc) {
        if (placedPos == null) { active = false; return; }

        faceToward(mc, Vec3.atCenterOf(placedPos));
        Direction face = getNearestFace(mc, placedPos);

        if (miningTicks == 0) {
            mc.gameMode.startDestroyBlock(placedPos, face);
        }

        SimulatedInput.hold(mc.options.keyAttack, this);
        miningTicks++;

        if (miningTicks % 2 == 0) {
            mc.gameMode.continueDestroyBlock(placedPos, face);
        }

        if (mc.level.getBlockState(placedPos).isAir()) {
            SimulatedInput.release(mc.options.keyAttack, this);
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
            waitTicks = 0;
            state = StorageState.WAITING_PICKUP;
            cooldown = 2;
            return;
        }

        if (miningTicks > MAX_MINING_TICKS) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_mine_failed");
            fail(mc);
        }
    }

    private StorageResult doWaitPickup(Minecraft mc) {
        waitTicks++;

        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_done");
                releaseKeys();
                active = false;
                state = StorageState.DONE;
                return StorageResult.DONE;
            }
        }

        if (waitTicks > MAX_PICKUP_WAIT_TICKS) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_pickup_failed");
            releaseKeys();
            active = false;
            state = StorageState.DONE;
            return StorageResult.FAILED;
        }

        return StorageResult.ACTIVE;
    }

    // ---- Helpers ----

    /**
     * Translate an inventory index into its slot index in the player's own inventory screen:
     * hotbar 0-8 sits at screen 36-44, main inventory 9-35 keeps its number.
     */
    private static int playerScreenSlot(int inventoryIndex) {
        return inventoryIndex < PlayerUtil.HOTBAR_SIZE
                ? InventoryMenu.USE_ROW_SLOT_START + inventoryIndex
                : inventoryIndex;
    }

    /**
     * @return whether the inventory holds anything a storage cycle could actually move: a
     *         non-shulker stack that is on the missing-materials list. Checked before starting a
     *         cycle — with nothing storable, a cycle would open a box, move nothing, close it and
     *         report DONE, and the still-full inventory would immediately start the next cycle,
     *         opening and closing the box forever.
     */
    public boolean hasStorableMaterials(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (mc.player == null) return false;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            // Boxes cannot nest, so a shulker box itself is never storable.
            if (ItemUtil.isShulkerBox(stack)) continue;
            if (isOnMissingList(stack, ctx)) return true;
        }
        return false;
    }

    /** @return whether another box is worth opening, so we do not cycle through known-full ones. */
    private boolean hasCandidateShulker(Minecraft mc, GatherContext ctx) {
        if (mc.player == null) return false;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (knownFullSlots.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!ItemUtil.isShulkerBox(stack)) continue;
            if (isOnMissingList(stack, ctx)) continue;
            return true;
        }
        return false;
    }

    /** Drop every key this feature holds. */
    public void releaseKeys() {
        SimulatedInput.releaseAll(this);
    }

    private void abort(Minecraft mc) {
        releaseKeys();
        if (mc.player != null) {
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
        }
        active = false;
    }

    /** Abort with a FAILED result, telling the task machine to stop entirely. */
    private void fail(Minecraft mc) {
        terminalResult = StorageResult.FAILED;
        abort(mc);
    }

    public void cancel(Minecraft mc) { abort(mc); }

    /** Called when auto-gathering starts fresh, clearing state kept across cycles. */
    public void resetKnownFullSlots() {
        knownFullSlots.clear();
    }

    private boolean isOnMissingList(ItemStack stack, GatherContext ctx) {
        for (MaterialItemEntry entry : ctx.missingItems) {
            if (ItemUtil.is(stack, entry.item)) return true;
        }
        return false;
    }

    /** @return whether the open box has a completely empty slot, i.e. room for a new item type. */
    private boolean hasEmptySlotInShulkerBox() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) return false;
        for (int i = 0; i < BOX_SLOT_COUNT; i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (slot == null || !slot.hasItem()) return true;
        }
        return false;
    }

    private boolean isShulkerBoxFull() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) return true;
        for (int i = 0; i < BOX_SLOT_COUNT; i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (slot == null || !slot.hasItem()) return false;
            if (slot.getItem().getCount() < slot.getItem().getMaxStackSize()) return false;
        }
        return true;
    }

    private Direction getNearestFace(Minecraft mc, BlockPos pos) {
        return ContainerOpener.nearestFace(mc.player.getEyePosition(), pos);
    }

    private void faceToward(Minecraft mc, Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double distH = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distH));
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
    }
}
