package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;

import com.alonediamond.playercontrolpp.compat.ContainerContentsCompat;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
// ClickType is not publicly exported in 26.1 API; use Inventory methods directly

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import java.lang.reflect.Method;

/**
 * Automatically fills waterloggable blocks in loaded Litematica schematics.
 * Uses a 6-state tick-driven state machine.
 *
 * State flow: SCANNING -> FINDING_BUCKET -> ROTATING -> PLACING_WATER -> COOLDOWN -> SCANNING
 * When no waterloggable blocks remain -> AUTO_STOP_COUNTDOWN (3-second timer).
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

    private static boolean enabled;
    private static State state = State.SCANNING;
    private static int stateTimer;
    private static int autoStopCountdown;
    private static BlockPos currentTarget;
    private static final List<BlockPos> waterloggableBlocks = new ArrayList<>();
    private static final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();

    // Cached reflection Method for calling getBlockState on schematic world
    private static Method schematicGetBlockStateMethod;
    private static Object lastSchematicWorld;

    /** Bounding box of a single schematic placement in world coordinates. */
    private record PlacementBounds(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + sizeX
                    && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + sizeY
                    && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + sizeZ;
        }
    }

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
            state = State.SCANNING;
            stateTimer = 0;
            autoStopCountdown = 0;
            currentTarget = null;
            waterloggableBlocks.clear();
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
        currentTarget = null;
        waterloggableBlocks.clear();
        schematicGetBlockStateMethod = null;
        lastSchematicWorld = null;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // Safety: pause while sneaking
        if (mc.player.isShiftKeyDown()) return;

        // Safety: disable if player is dead
        if (mc.player.isDeadOrDying()) {
            enabled = false;
            resetState();
            return;
        }

        switch (state) {
            case SCANNING -> tickScanning(mc);
            case FINDING_BUCKET -> tickFindingBucket(mc);
            case SHULKERING -> getWaterBucketFromShulkerBox(mc);
            case ROTATING -> tickRotating(mc);
            case PLACING_WATER -> tickPlacingWater(mc);
            case COOLDOWN -> tickCooldown(mc);
            case AUTO_STOP_COUNTDOWN -> tickAutoStopCountdown(mc);
        }
    }

    // -------------------------------------------------------------------------
    // State: SHULKERING(getWaterBucketFromShulkerBox)
    // -------------------------------------------------------------------------

    private static void getWaterBucketFromShulkerBox(Minecraft mc) {
        --stateTimer;
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)){
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            state = State.AUTO_STOP_COUNTDOWN;
            return;
        }

        if (!(stateTimer <=0)){
            AbstractContainerMenu handler = mc.player.containerMenu;
            if (handler == null) {
                mc.player.closeContainer();
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                state = State.AUTO_STOP_COUNTDOWN;
            }

            if (handler instanceof InventoryMenu) System.out.println("InventoryMenu");
            List<Slot> slots = handler.slots;
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (slot.container == mc.player.getInventory()) continue;

                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                if (isWaterBucket(stack)) {
                    SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
                }
            }
            mc.player.closeContainer();
            state = State.ROTATING;
        }else {
            state = State.AUTO_STOP_COUNTDOWN;
        }
    }

    // -------------------------------------------------------------------------
    // State: SCANNING
    // -------------------------------------------------------------------------

    private static void tickScanning(Minecraft mc) {
        int configRadius = Configs.Settings.WATER_FILL_SCAN_RADIUS.getIntegerValue();
        double playerReach = mc.player.isCreative() ? 5.0 : 4.5;
        int radius = Math.min(configRadius, (int) Math.floor(playerReach));

        BlockPos playerPos = mc.player.blockPosition();
        waterloggableBlocks.clear();
        currentTarget = null;

        List<BlockPos> found = scanWaterloggableBlocks(mc, playerPos, radius);
        waterloggableBlocks.addAll(found);

        if (waterloggableBlocks.isEmpty()) {
            state = State.AUTO_STOP_COUNTDOWN;
            autoStopCountdown = 60; // 3 seconds at 20 tps
        } else {
            currentTarget = waterloggableBlocks.get(0);
            state = State.FINDING_BUCKET;
        }
    }

    // -------------------------------------------------------------------------
    // State: FINDING_BUCKET
    // -------------------------------------------------------------------------

    private static void tickFindingBucket(Minecraft mc) {
        Inventory inv = mc.player.getInventory();

        // 1) Search hotbar (main slots 0-8)
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isWaterBucket(stack)) {
                selectHotbarSlot(mc, i);
                state = State.ROTATING;
                return;
            }
        }

        // 2) Not in hotbar, search main inventory (slots 9-35)
        int bucketSlot = -1;
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isWaterBucket(stack)) {
                bucketSlot = i;
                break;
            }
        }

        int bucketCountFromShulkers = 0;
        int haveBucketShulkerBoxScreenSlot = 0;
        if (bucketSlot < 0) {
            //尝试寻找潜影盒
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                    ItemContainerContents c = s.get(DataComponents.CONTAINER);
                    if (c != null) {
                        for (ItemStack inner : ContainerContentsCompat.nonEmptyItems(c)) {
                            if (inner.is(Items.WATER_BUCKET)){
                                bucketCountFromShulkers += inner.getCount();
                                haveBucketShulkerBoxScreenSlot = i;
                                break;
                            }
                        }
                    }
                }
            }
            if (bucketCountFromShulkers <= 0){
                // No water bucket anywhere
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.no_bucket");
                enabled = false;
                resetState();
                return;
            }
        }

        // 3) Find swap target in hotbar (empty slot first, then selected slot)
        int targetHotbar = -1;
        for (int i = 0; i <= 8; i++) {
            if (inv.getItem(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }
        if (targetHotbar < 0) {
            targetHotbar = InventoryCompat.getSelectedSlot(inv);
        }

        if (bucketCountFromShulkers <= 0){
            // 4) Swap inventory slot with hotbar slot
            swapSlotWithHotbar(mc, bucketSlot, targetHotbar);
            // Select the hotbar slot we just put the bucket into
            selectHotbarSlot(mc, targetHotbar);
            state = State.ROTATING;
        }else {
            if (!quickShulker.isLoaded()) {
                state = State.AUTO_STOP_COUNTDOWN;
                return;
            }
            if (!quickShulker.openShulkerBox(haveBucketShulkerBoxScreenSlot)) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                state = State.AUTO_STOP_COUNTDOWN;
                return;
            }else {
                stateTimer = 10;
                state = State.SHULKERING;
            }
        }

    }

    /**
     * Select a hotbar slot and sync the selection to the server.
     * Setting the selected slot directly only changes the client side;
     * without the ServerboundSetCarriedItemPacket, the server still thinks
     * the player is holding whatever was selected before — causing
     * useItemOn to silently fail.
     */
    private static void selectHotbarSlot(Minecraft mc, int slot) {
        InventoryCompat.setSelectedSlot(mc.player.getInventory(), slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
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

        // Smooth rotation toward target (max 20 degrees per tick)
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;
        float maxStep = 20.0f;
        float stepYaw = Mth.clamp(deltaYaw, -maxStep, maxStep);
        float stepPitch = Mth.clamp(deltaPitch, -maxStep, maxStep);

        float newYaw = currentYaw + stepYaw;
        float newPitch = Mth.clamp(currentPitch + stepPitch, -90.0f, 90.0f);

        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
        mc.player.setYHeadRot(newYaw);

        // Check if facing is close enough
        float remainingYaw = Math.abs(Mth.wrapDegrees(targetYaw - mc.player.getYRot()));
        float remainingPitch = Math.abs(targetPitch - mc.player.getXRot());
        if (remainingYaw <= 2.0f && remainingPitch <= 1.0f) {
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

        // Verify bucket is still in hand
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !isWaterBucket(held)) {
            waterloggableBlocks.remove(currentTarget);
            currentTarget = null;
            state = State.SCANNING;
            return;
        }

        // Verify world block state: not already waterlogged
        BlockState worldState = mc.level.getBlockState(currentTarget);
        if (worldState.hasProperty(BlockStateProperties.WATERLOGGED) && worldState.getValue(BlockStateProperties.WATERLOGGED)) {
            waterloggableBlocks.remove(currentTarget);
            currentTarget = null;
            state = State.SCANNING;
            return;
        }

        // Verify schematic state via cached reflection
        Object schematicWorld = LitematicaIntegration.getInstance().getSchematicWorld();
        if (schematicWorld != null) {
            if (schematicWorld != lastSchematicWorld || schematicGetBlockStateMethod == null) {
                lastSchematicWorld = schematicWorld;
                try {
                    schematicGetBlockStateMethod = schematicWorld.getClass()
                            .getMethod("getBlockState", BlockPos.class);
                } catch (Exception e) {
                    schematicGetBlockStateMethod = null;
                }
            }
            if (schematicGetBlockStateMethod != null) {
                try {
                    Object stateObj = schematicGetBlockStateMethod.invoke(
                            schematicWorld, currentTarget);
                    if (stateObj instanceof BlockState schemState) {
                        boolean sameBlock = worldState.getBlock() == schemState.getBlock();
                        boolean wantsWaterlog = schemState.hasProperty(BlockStateProperties.WATERLOGGED)
                                && schemState.getValue(BlockStateProperties.WATERLOGGED);
                        if (!sameBlock || !wantsWaterlog) {
                            waterloggableBlocks.remove(currentTarget);
                            currentTarget = null;
                            state = State.SCANNING;
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Simulate right-click on the target block.
        // Both useItemOn AND useItem are needed: useItemOn sends
        // the block-targeted packet, useItem ensures the item's use-on-block
        // action (BucketItem.useOnBlock) is triggered server-side for waterlogging.
        Vec3 center = Vec3.atCenterOf(currentTarget);
        BlockHitResult hitResult = new BlockHitResult(center, Direction.UP, currentTarget, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

        int delay = Configs.Settings.WATER_FILL_OPERATION_DELAY.getIntegerValue();
        state = State.COOLDOWN;
        stateTimer = delay;
    }

    // -------------------------------------------------------------------------
    // State: COOLDOWN
    // -------------------------------------------------------------------------

    private static void tickCooldown(Minecraft mc) {
        stateTimer--;
        if (stateTimer <= 0) {
            // Remove the just-processed block and scan for the next one
            if (currentTarget != null) {
                waterloggableBlocks.remove(currentTarget);
                currentTarget = null;
            }
            state = State.SCANNING;
        }
    }

    // -------------------------------------------------------------------------
    // State: AUTO_STOP_COUNTDOWN
    // -------------------------------------------------------------------------

    private static void tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        // Re-scan: if new blocks appear, cancel countdown and resume
        int configRadius = Configs.Settings.WATER_FILL_SCAN_RADIUS.getIntegerValue();
        double playerReach = mc.player.isCreative() ? 5.0 : 4.5;
        int radius = Math.min(configRadius, (int) Math.floor(playerReach));
        BlockPos playerPos = mc.player.blockPosition();

        List<BlockPos> found = scanWaterloggableBlocks(mc, playerPos, radius);
        if (!found.isEmpty()) {
            // New blocks appeared, cancel auto-stop
            waterloggableBlocks.clear();
            waterloggableBlocks.addAll(found);
            currentTarget = waterloggableBlocks.get(0);
            state = State.FINDING_BUCKET;
            return;
        }

        if (autoStopCountdown <= 0) {
            enabled = false;
            resetState();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.completed");
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Scans for waterloggable blocks that match the schematic but are not yet
     * waterlogged in the world.
     *
     * @param mc         the Minecraft client instance
     * @param playerPos  the player's block position
     * @param radius     the scan radius (clamped to player reach)
     * @return sorted list of BlockPos candidates (nearest first)
     */
    private static List<BlockPos> scanWaterloggableBlocks(Minecraft mc, BlockPos playerPos, int radius) {
        List<BlockPos> result = new ArrayList<>();

        BlockGetter schematicWorld = null;
        try {
            Object sw = LitematicaIntegration.getInstance().getSchematicWorld();
            if (sw instanceof BlockGetter bv) {
                schematicWorld = bv;
            }
        } catch (Exception ignored) {
        }

        if (schematicWorld == null) return result;

        List<PlacementBounds> bounds = getPlacementBounds();
        if (bounds.isEmpty()) return result;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > radius * radius) continue;

                    BlockPos pos = playerPos.offset(dx, dy, dz);

                    // Only process positions within a schematic placement
                    boolean inPlacement = false;
                    for (PlacementBounds b : bounds) {
                        if (b.contains(pos)) {
                            inPlacement = true;
                            break;
                        }
                    }
                    if (!inPlacement) continue;

                    BlockState worldState = mc.level.getBlockState(pos);
                    BlockState schematicState;
                    try {
                        schematicState = schematicWorld.getBlockState(pos);
                    } catch (Exception e) {
                        continue;
                    }

                    // Check: schematic wants waterlogging, world block matches, world NOT waterlogged
                    if (schematicState.hasProperty(BlockStateProperties.WATERLOGGED)
                            && schematicState.getValue(BlockStateProperties.WATERLOGGED)
                            && worldState.getBlock().equals(schematicState.getBlock())
                            && (!worldState.hasProperty(BlockStateProperties.WATERLOGGED) || !worldState.getValue(BlockStateProperties.WATERLOGGED))) {
                        result.add(pos.immutable());
                    }
                }
            }
        }

        result.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));
        return result;
    }

    /**
     * Retrieves the world-space bounding boxes of all loaded Litematica schematic
     * placements via reflection.
     */
    private static List<PlacementBounds> getPlacementBounds() {
        List<PlacementBounds> result = new ArrayList<>();
        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            // All DataManager methods are static — no getInstance()
            Object spm = dmClass.getMethod("getSchematicPlacementManager").invoke(null);
            if (spm == null) return result;

            // Try multiple method names for getting placement list
            List<?> placements = null;
            String[] spmMethods = {"getAllSchematicPlacements", "getAllSchematicsPlacements",
                    "getSchematicPlacements", "getLoadedSchematicPlacements"};
            for (String name : spmMethods) {
                try {
                    Object r = spm.getClass().getMethod(name).invoke(spm);
                    if (r instanceof List) { placements = (List<?>) r; break; }
                } catch (Exception ignored) {}
            }
            if (placements == null) return result;

            for (Object placement : placements) {
                try {
                    BlockPos origin = (BlockPos) placement.getClass().getMethod("getOrigin").invoke(placement);
                    Object schematic = placement.getClass().getMethod("getSchematic").invoke(placement);
                    Vec3i size = (Vec3i) schematic.getClass().getMethod("getTotalSize").invoke(schematic);
                    result.add(new PlacementBounds(origin, size.getX(), size.getY(), size.getZ()));
                } catch (Exception ignored) {
                    // Skip placements that fail reflection
                }
            }
        } catch (Exception ignored) {
            // Litematica not available — return empty
        }
        return result;
    }

    /**
     * Returns {@code true} if the given ItemStack is a water bucket.
     */
    private static boolean isWaterBucket(ItemStack stack) {
        return stack.getItem() == Items.WATER_BUCKET;
    }

    /**
     * Swaps the item in {@code inventorySlot} (Inventory.items index 9-35)
     * with the item in {@code hotbarSlot} (Inventory.items index 0-8).
     */
    private static void swapSlotWithHotbar(Minecraft mc, int inventorySlot, int hotbarSlot) {
        /*Inventory inv = mc.player.getInventory();
        ItemStack invStack = inv.getItem(inventorySlot);
        ItemStack hotStack = inv.getItem(hotbarSlot);
        inv.setItem(inventorySlot, hotStack);
        inv.setItem(hotbarSlot, invStack);*/
        int syncId = mc.player.inventoryMenu.containerId;
        int screenInvSlot = inventorySlot;          // main[9-35] → screen 9-35
        int screenHotbarSlot = 36 + hotbarSlot;      // main[0-8]  → screen 36-44
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
        SlotActionCompat.pickup(mc, syncId, screenHotbarSlot);
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
    }
}
