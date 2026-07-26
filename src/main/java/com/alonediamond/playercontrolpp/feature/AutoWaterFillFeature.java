package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.PlacementBounds;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Waterlogs blocks that a loaded Litematica schematic wants waterlogged but the world does not
 * have filled yet.
 *
 * <pre>
 * SCANNING -&gt; FINDING_BUCKET -&gt; [SHULKERING] -&gt; ROTATING -&gt; PLACING_WATER -&gt; COOLDOWN -&gt; SCANNING
 * </pre>
 *
 * <p>SHULKERING is entered only when no loose water bucket is left but one is sitting inside a
 * shulker box in the inventory and QuickShulker is installed to open it in place.
 *
 * <p>When nothing in range needs water the feature enters AUTO_STOP_COUNTDOWN and keeps
 * re-scanning at a reduced rate for three seconds, so walking to the next spot resumes it
 * without another hotkey press.
 */
public class AutoWaterFillFeature {

    private enum State {
        SCANNING,
        FINDING_BUCKET,
        SHULKERING,
        ROTATING,
        PLACING_WATER,
        COOLDOWN,
        AUTO_STOP_COUNTDOWN
    }

    /** Auto-stop grace period: 3 seconds at 20 tps. */
    private static final int AUTO_STOP_TICKS = 60;
    /** During the countdown, only re-scan this often — it is otherwise a per-tick 11³ sweep. */
    private static final int COUNTDOWN_SCAN_INTERVAL = 5;
    /** Ticks to wait for a QuickShulker-opened container screen before giving up. */
    private static final int SHULKER_OPEN_WAIT_TICKS = 20;
    /** How many times to try pulling a bucket out of a shulker box before stopping. */
    private static final int MAX_SHULKER_ATTEMPTS = 3;
    /** Max rotation per tick while aiming, in degrees. */
    private static final float MAX_TURN_STEP = 20.0f;
    /** Aim tolerance before clicking, in degrees. */
    private static final float AIM_YAW_TOLERANCE = 2.0f;
    private static final float AIM_PITCH_TOLERANCE = 1.0f;
    /**
     * How long a block stays on the "just tried it" list. The server takes a few ticks to echo
     * the new waterlogged state back, and without this the next scan re-targets the same block
     * and right-clicks it again.
     */
    private static final int RETRY_BLOCK_COOLDOWN = 20;

    private static boolean enabled;
    private static State state = State.SCANNING;
    private static int stateTimer;
    private static int autoStopCountdown;
    private static int tickCounter;
    private static int shulkerAttempts;
    private static BlockPos currentTarget;
    /** pos -&gt; the tick at which it becomes a candidate again. */
    private static final Map<BlockPos, Integer> recentlyAttempted = new HashMap<>();
    private static final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();

    /** Cached handle for {@code schematicWorld.getBlockState(BlockPos)}. */
    private static Method schematicGetBlockStateMethod;
    private static Object lastSchematicWorld;

    /** Registered with {@link FeatureRegistry}; see {@code InitHandler}. */
    public static final ClientFeature FEATURE = new ClientFeature() {
        @Override public void onClientTick(Minecraft mc) { tick(mc); }
        @Override public void onWorldChange() { AutoWaterFillFeature.onWorldChange(); }
        @Override public boolean isActive() { return enabled; }
    };

    private AutoWaterFillFeature() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        enabled = !enabled;
        if (enabled) {
            if (!LitematicaIntegration.getInstance().isSchematicLoaded()) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.water_fill.no_schematic");
                enabled = false;
                return;
            }
            MessageUtil.sendActionBar(client, "playercontrolpp.message.water_fill.on");
            resetState();
        } else {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.water_fill.off");
            resetState();
        }
    }

    public static void onWorldChange() {
        if (enabled) {
            enabled = false;
            resetState();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.water_fill.world_change");
            }
        }
    }

    private static void resetState() {
        state = State.SCANNING;
        stateTimer = 0;
        autoStopCountdown = 0;
        shulkerAttempts = 0;
        currentTarget = null;
        recentlyAttempted.clear();
        schematicGetBlockStateMethod = null;
        lastSchematicWorld = null;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // Safety: pause while sneaking, so the player can always take back control.
        if (mc.player.isShiftKeyDown()) return;

        if (mc.player.isDeadOrDying()) {
            enabled = false;
            resetState();
            return;
        }

        tickCounter++;
        expireRetryCooldowns();

        switch (state) {
            case SCANNING -> tickScanning(mc);
            case FINDING_BUCKET -> tickFindingBucket(mc);
            case SHULKERING -> tickShulkering(mc);
            case ROTATING -> tickRotating(mc);
            case PLACING_WATER -> tickPlacingWater(mc);
            case COOLDOWN -> tickCooldown(mc);
            case AUTO_STOP_COUNTDOWN -> tickAutoStopCountdown(mc);
        }
    }

    // -------------------------------------------------------------------------
    // State: SCANNING
    // -------------------------------------------------------------------------

    private static void tickScanning(Minecraft mc) {
        currentTarget = findNearestTarget(mc);
        if (currentTarget == null) {
            beginAutoStopCountdown();
        } else {
            state = State.FINDING_BUCKET;
        }
    }

    private static void beginAutoStopCountdown() {
        state = State.AUTO_STOP_COUNTDOWN;
        autoStopCountdown = AUTO_STOP_TICKS;
    }

    // -------------------------------------------------------------------------
    // State: FINDING_BUCKET
    // -------------------------------------------------------------------------

    private static void tickFindingBucket(Minecraft mc) {
        Inventory inv = mc.player.getInventory();

        // 1) Already in the hotbar — just select it.
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            if (isWaterBucket(inv.getItem(i))) {
                selectHotbarSlot(mc, i);
                shulkerAttempts = 0;
                state = State.ROTATING;
                return;
            }
        }

        // 2) In the main inventory — swap it down into the hotbar.
        int bucketSlot = -1;
        for (int i = PlayerUtil.HOTBAR_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            if (isWaterBucket(inv.getItem(i))) {
                bucketSlot = i;
                break;
            }
        }
        if (bucketSlot >= 0) {
            int targetHotbar = firstFreeHotbarSlot(inv);
            swapSlotWithHotbar(mc, bucketSlot, targetHotbar);
            selectHotbarSlot(mc, targetHotbar);
            shulkerAttempts = 0;
            state = State.ROTATING;
            return;
        }

        // 3) Last resort: a bucket inside a shulker box, opened in place via QuickShulker.
        int shulkerSlot = findShulkerSlotWithWaterBucket(inv);
        if (shulkerSlot < 0) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.no_bucket");
            enabled = false;
            resetState();
            return;
        }
        if (!quickShulker.isLoaded() || shulkerAttempts >= MAX_SHULKER_ATTEMPTS) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.no_bucket");
            enabled = false;
            resetState();
            return;
        }

        // QuickShulker addresses slots by container-screen index, not inventory index.
        int screenSlot = shulkerSlot < PlayerUtil.HOTBAR_SIZE
                ? InventoryMenu.USE_ROW_SLOT_START + shulkerSlot
                : shulkerSlot;
        if (!quickShulker.openShulkerBox(screenSlot)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            beginAutoStopCountdown();
            return;
        }
        shulkerAttempts++;
        stateTimer = SHULKER_OPEN_WAIT_TICKS;
        state = State.SHULKERING;
    }

    /** @return an empty hotbar slot, or the currently selected one if the hotbar is full. */
    private static int firstFreeHotbarSlot(Inventory inv) {
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return InventoryCompat.getSelectedSlot(inv);
    }

    /** @return the inventory index of a shulker box holding a water bucket, or -1. */
    private static int findShulkerSlotWithWaterBucket(Inventory inv) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemUtil.isShulkerBox(stack) && ItemUtil.containsInside(stack, Items.WATER_BUCKET)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Select a hotbar slot and tell the server about it.
     *
     * <p>Setting the selected slot only changes the client; without
     * ServerboundSetCarriedItemPacket the server still believes the previous item is held, and
     * {@code useItemOn} silently does nothing.
     */
    private static void selectHotbarSlot(Minecraft mc, int slot) {
        InventoryCompat.setSelectedSlot(mc.player.getInventory(), slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }

    // -------------------------------------------------------------------------
    // State: SHULKERING
    // -------------------------------------------------------------------------

    /**
     * Waits for the QuickShulker-opened screen, pulls out exactly one water bucket, closes up
     * and goes back through FINDING_BUCKET so the bucket ends up selected in the hotbar.
     */
    private static void tickShulkering(Minecraft mc) {
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            // The packet round-trip takes a few ticks; only fail once the wait runs out.
            if (--stateTimer <= 0) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                beginAutoStopCountdown();
            }
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;
        boolean tookOne = false;
        for (Slot slot : handler.slots) {
            // Skip the player-inventory half of the screen; we only want the box's own slots.
            if (slot.container == mc.player.getInventory()) continue;
            if (!isWaterBucket(slot.getItem())) continue;
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            tookOne = true;
            break; // one bucket is all we need — the original emptied the whole box
        }

        mc.player.closeContainer();
        if (tookOne) {
            state = State.FINDING_BUCKET;
        } else {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.no_bucket");
            enabled = false;
            resetState();
        }
    }

    // -------------------------------------------------------------------------
    // State: ROTATING
    // -------------------------------------------------------------------------

    private static void tickRotating(Minecraft mc) {
        if (currentTarget == null) {
            state = State.SCANNING;
            return;
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(currentTarget);
        double dx = targetCenter.x - eyePos.x;
        double dy = targetCenter.y - eyePos.y;
        double dz = targetCenter.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        float stepYaw = Mth.clamp(Mth.wrapDegrees(targetYaw - currentYaw), -MAX_TURN_STEP, MAX_TURN_STEP);
        float stepPitch = Mth.clamp(targetPitch - currentPitch, -MAX_TURN_STEP, MAX_TURN_STEP);

        float newYaw = currentYaw + stepYaw;
        float newPitch = Mth.clamp(currentPitch + stepPitch, -90.0f, 90.0f);

        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
        mc.player.setYHeadRot(newYaw);

        float remainingYaw = Math.abs(Mth.wrapDegrees(targetYaw - newYaw));
        float remainingPitch = Math.abs(targetPitch - newPitch);
        if (remainingYaw <= AIM_YAW_TOLERANCE && remainingPitch <= AIM_PITCH_TOLERANCE) {
            state = State.PLACING_WATER;
        }
    }

    // -------------------------------------------------------------------------
    // State: PLACING_WATER
    // -------------------------------------------------------------------------

    private static void tickPlacingWater(Minecraft mc) {
        if (currentTarget == null || mc.gameMode == null) {
            state = State.SCANNING;
            return;
        }

        // The bucket emptied, or something else got selected — keep the target, re-arm the hand.
        if (!isWaterBucket(mc.player.getMainHandItem())) {
            state = State.FINDING_BUCKET;
            return;
        }

        BlockState worldState = mc.level.getBlockState(currentTarget);
        if (isWaterlogged(worldState)) {
            abandonTarget();
            return;
        }
        if (!schematicWantsWaterAt(mc, currentTarget, worldState)) {
            abandonTarget();
            return;
        }

        // Both calls are needed: useItemOn sends the block-targeted packet, useItem makes the
        // server run BucketItem's use-on-block path that actually waterlogs.
        Vec3 center = Vec3.atCenterOf(currentTarget);
        BlockHitResult hitResult = new BlockHitResult(center, Direction.UP, currentTarget, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

        markAttempted(currentTarget);
        currentTarget = null;
        state = State.COOLDOWN;
        stateTimer = Configs.Settings.WATER_FILL_OPERATION_DELAY.getIntegerValue();
    }

    /** Give up on the current target without counting it as an attempt worth cooling down. */
    private static void abandonTarget() {
        if (currentTarget != null) {
            markAttempted(currentTarget);
            currentTarget = null;
        }
        state = State.SCANNING;
    }

    // -------------------------------------------------------------------------
    // State: COOLDOWN
    // -------------------------------------------------------------------------

    private static void tickCooldown(Minecraft mc) {
        if (--stateTimer <= 0) {
            state = State.SCANNING;
        }
    }

    // -------------------------------------------------------------------------
    // State: AUTO_STOP_COUNTDOWN
    // -------------------------------------------------------------------------

    private static void tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        // Re-scan periodically rather than every tick: the sweep is the expensive part and the
        // player cannot walk far in a quarter of a second.
        if (autoStopCountdown % COUNTDOWN_SCAN_INTERVAL == 0) {
            BlockPos found = findNearestTarget(mc);
            if (found != null) {
                currentTarget = found;
                state = State.FINDING_BUCKET;
                return;
            }
        }

        if (autoStopCountdown <= 0) {
            enabled = false;
            resetState();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.completed");
        }
    }

    // =========================================================================
    // Scanning
    // =========================================================================

    /**
     * Sweeps the reach-limited cube around the player for the closest block the schematic wants
     * waterlogged and the world does not have filled.
     *
     * <p>Single pass with a reused {@link BlockPos.MutableBlockPos}: the old version allocated a
     * BlockPos per candidate, collected every hit into a list and then sorted the whole list only
     * to read element 0.
     *
     * @return the nearest candidate, or {@code null} if there is none
     */
    private static BlockPos findNearestTarget(Minecraft mc) {
        BlockGetter schematicWorld = schematicWorldOrNull();
        if (schematicWorld == null) return null;

        List<PlacementBounds> bounds = LitematicaIntegration.getInstance().getPlacementBounds();
        if (bounds.isEmpty()) return null;

        int configRadius = Configs.Settings.WATER_FILL_SCAN_RADIUS.getIntegerValue();
        int radius = Math.min(configRadius, (int) Math.floor(PlayerUtil.blockReach(mc.player)));
        BlockPos playerPos = mc.player.blockPosition();
        int px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
        int radiusSq = radius * radius;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > radiusSq || distSq >= bestDistSq) continue;

                    cursor.set(px + dx, py + dy, pz + dz);
                    if (recentlyAttempted.containsKey(cursor)) continue;
                    if (!inAnyPlacement(bounds, cursor)) continue;

                    BlockState schematicState;
                    try {
                        schematicState = schematicWorld.getBlockState(cursor);
                    } catch (Exception e) {
                        continue; // outside the schematic's own bounds
                    }
                    if (!isWaterlogged(schematicState)) continue;

                    BlockState worldState = mc.level.getBlockState(cursor);
                    if (worldState.getBlock() != schematicState.getBlock()) continue;
                    if (isWaterlogged(worldState)) continue;

                    best = cursor.immutable(); // must copy: the cursor keeps moving
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    private static boolean inAnyPlacement(List<PlacementBounds> bounds, BlockPos pos) {
        for (int i = 0; i < bounds.size(); i++) {
            if (bounds.get(i).contains(pos)) return true;
        }
        return false;
    }

    private static boolean isWaterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static BlockGetter schematicWorldOrNull() {
        Object schematicWorld = LitematicaIntegration.getInstance().getSchematicWorld();
        return schematicWorld instanceof BlockGetter view ? view : null;
    }

    /**
     * Confirms against the schematic that {@code pos} really should be waterlogged, and that the
     * world block there is the one the schematic expects. Re-checked immediately before clicking
     * because the scan may be several ticks old by then.
     */
    private static boolean schematicWantsWaterAt(Minecraft mc, BlockPos pos, BlockState worldState) {
        Object schematicWorld = LitematicaIntegration.getInstance().getSchematicWorld();
        if (schematicWorld == null) return true; // no schematic to contradict us

        if (schematicWorld != lastSchematicWorld || schematicGetBlockStateMethod == null) {
            lastSchematicWorld = schematicWorld;
            try {
                schematicGetBlockStateMethod = schematicWorld.getClass()
                        .getMethod("getBlockState", BlockPos.class);
            } catch (NoSuchMethodException e) {
                schematicGetBlockStateMethod = null;
            }
        }
        if (schematicGetBlockStateMethod == null) return true;

        try {
            Object stateObj = schematicGetBlockStateMethod.invoke(schematicWorld, pos);
            if (stateObj instanceof BlockState schemState) {
                return worldState.getBlock() == schemState.getBlock() && isWaterlogged(schemState);
            }
        } catch (Exception ignored) {
            // Litematica internals changed shape — fall through and trust the scan.
        }
        return true;
    }

    // =========================================================================
    // Retry cooldown bookkeeping
    // =========================================================================

    private static void markAttempted(BlockPos pos) {
        recentlyAttempted.put(pos.immutable(), tickCounter + RETRY_BLOCK_COOLDOWN);
    }

    private static void expireRetryCooldowns() {
        if (recentlyAttempted.isEmpty()) return;
        recentlyAttempted.values().removeIf(expiry -> expiry <= tickCounter);
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    private static boolean isWaterBucket(ItemStack stack) {
        return ItemUtil.is(stack, Items.WATER_BUCKET);
    }

    /**
     * Swap the item in {@code inventorySlot} (Inventory index 9-35) with the one in
     * {@code hotbarSlot} (Inventory index 0-8), via three container clicks so the server agrees.
     */
    private static void swapSlotWithHotbar(Minecraft mc, int inventorySlot, int hotbarSlot) {
        int syncId = mc.player.inventoryMenu.containerId;
        // Inventory indices and screen slot indices are not the same numbering:
        //   main[9-35] -> screen 9-35, main[0-8] -> screen 36-44.
        int screenInvSlot = inventorySlot;
        int screenHotbarSlot = InventoryMenu.USE_ROW_SLOT_START + hotbarSlot;
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
        SlotActionCompat.pickup(mc, syncId, screenHotbarSlot);
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
    }
}
