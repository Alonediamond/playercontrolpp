package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;

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

    public static void toggle(MinecraftClient client) {
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
            MinecraftClient client = MinecraftClient.getInstance();
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

    public static void tick(MinecraftClient mc) {
        if (!enabled || mc.player == null || mc.world == null) return;

        // Safety: pause while sneaking
        if (mc.player.isSneaking()) return;

        // Safety: disable if player is dead
        if (mc.player.isDead()) {
            enabled = false;
            resetState();
            return;
        }

        switch (state) {
            case SCANNING -> tickScanning(mc);
            case FINDING_BUCKET -> tickFindingBucket(mc);
            case ROTATING -> tickRotating(mc);
            case PLACING_WATER -> tickPlacingWater(mc);
            case COOLDOWN -> tickCooldown(mc);
            case AUTO_STOP_COUNTDOWN -> tickAutoStopCountdown(mc);
        }
    }

    // -------------------------------------------------------------------------
    // State: SCANNING
    // -------------------------------------------------------------------------

    private static void tickScanning(MinecraftClient mc) {
        int configRadius = Configs.Settings.WATER_FILL_SCAN_RADIUS.getIntegerValue();
        double playerReach = mc.player.isCreative() ? 5.0 : 4.5;
        int radius = Math.min(configRadius, (int) Math.floor(playerReach));

        BlockPos playerPos = mc.player.getBlockPos();
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

    private static void tickFindingBucket(MinecraftClient mc) {
        PlayerInventory inv = mc.player.getInventory();

        // 1) Search hotbar (main slots 0-8)
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = inv.main.get(i);
            if (!stack.isEmpty() && isWaterBucket(stack)) {
                selectHotbarSlot(mc, i);
                state = State.ROTATING;
                return;
            }
        }

        // 2) Not in hotbar, search main inventory (slots 9-35)
        int bucketSlot = -1;
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = inv.main.get(i);
            if (!stack.isEmpty() && isWaterBucket(stack)) {
                bucketSlot = i;
                break;
            }
        }

        if (bucketSlot < 0) {
            // No water bucket anywhere
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.water_fill.no_bucket");
            enabled = false;
            resetState();
            return;
        }

        // 3) Find swap target in hotbar (empty slot first, then selected slot)
        int targetHotbar = -1;
        for (int i = 0; i <= 8; i++) {
            if (inv.main.get(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }
        if (targetHotbar < 0) {
            targetHotbar = inv.selectedSlot;
        }

        // 4) Swap inventory slot with hotbar slot
        swapSlotWithHotbar(mc, bucketSlot, targetHotbar);
        // Select the hotbar slot we just put the bucket into
        selectHotbarSlot(mc, targetHotbar);
        state = State.ROTATING;
    }

    /**
     * Select a hotbar slot and sync the selection to the server.
     * Setting inv.selectedSlot directly only changes the client side;
     * without the UpdateSelectedSlotC2SPacket, the server still thinks
     * the player is holding whatever was selected before — causing
     * interactBlock to silently fail.
     */
    private static void selectHotbarSlot(MinecraftClient mc, int slot) {
        mc.player.getInventory().selectedSlot = slot;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(
                    new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
        }
    }

    // -------------------------------------------------------------------------
    // State: ROTATING
    // -------------------------------------------------------------------------

    private static void tickRotating(MinecraftClient mc) {
        if (currentTarget == null) {
            state = State.SCANNING;
            return;
        }

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget);
        double dx = targetCenter.x - eyePos.x;
        double dy = targetCenter.y - eyePos.y;
        double dz = targetCenter.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        // Smooth rotation toward target (max 20 degrees per tick)
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;
        float maxStep = 20.0f;
        float stepYaw = MathHelper.clamp(deltaYaw, -maxStep, maxStep);
        float stepPitch = MathHelper.clamp(deltaPitch, -maxStep, maxStep);

        float newYaw = currentYaw + stepYaw;
        float newPitch = MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
        mc.player.setHeadYaw(newYaw);

        // Check if facing is close enough
        float remainingYaw = Math.abs(MathHelper.wrapDegrees(targetYaw - mc.player.getYaw()));
        float remainingPitch = Math.abs(targetPitch - mc.player.getPitch());
        if (remainingYaw <= 2.0f && remainingPitch <= 1.0f) {
            state = State.PLACING_WATER;
        }
    }

    // -------------------------------------------------------------------------
    // State: PLACING_WATER
    // -------------------------------------------------------------------------

    private static void tickPlacingWater(MinecraftClient mc) {
        if (currentTarget == null || mc.interactionManager == null) {
            state = State.SCANNING;
            return;
        }

        // Verify bucket is still in hand
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty() || !isWaterBucket(held)) {
            waterloggableBlocks.remove(currentTarget);
            currentTarget = null;
            state = State.SCANNING;
            return;
        }

        // Verify world block state: not already waterlogged
        BlockState worldState = mc.world.getBlockState(currentTarget);
        if (worldState.contains(Properties.WATERLOGGED) && worldState.get(Properties.WATERLOGGED)) {
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
                        boolean wantsWaterlog = schemState.contains(Properties.WATERLOGGED)
                                && schemState.get(Properties.WATERLOGGED);
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
        // Both interactBlock AND interactItem are needed: interactBlock sends
        // the block-targeted packet, interactItem ensures the item's use-on-block
        // action (BucketItem.useOnBlock) is triggered server-side for waterlogging.
        Vec3d center = Vec3d.ofCenter(currentTarget);
        BlockHitResult hitResult = new BlockHitResult(center, Direction.UP, currentTarget, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        int delay = Configs.Settings.WATER_FILL_OPERATION_DELAY.getIntegerValue();
        state = State.COOLDOWN;
        stateTimer = delay;
    }

    // -------------------------------------------------------------------------
    // State: COOLDOWN
    // -------------------------------------------------------------------------

    private static void tickCooldown(MinecraftClient mc) {
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

    private static void tickAutoStopCountdown(MinecraftClient mc) {
        autoStopCountdown--;

        // Re-scan: if new blocks appear, cancel countdown and resume
        int configRadius = Configs.Settings.WATER_FILL_SCAN_RADIUS.getIntegerValue();
        double playerReach = mc.player.isCreative() ? 5.0 : 4.5;
        int radius = Math.min(configRadius, (int) Math.floor(playerReach));
        BlockPos playerPos = mc.player.getBlockPos();

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
    private static List<BlockPos> scanWaterloggableBlocks(MinecraftClient mc, BlockPos playerPos, int radius) {
        List<BlockPos> result = new ArrayList<>();

        BlockView schematicWorld = null;
        try {
            Object sw = LitematicaIntegration.getInstance().getSchematicWorld();
            if (sw instanceof BlockView bv) {
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

                    BlockPos pos = playerPos.add(dx, dy, dz);

                    // Only process positions within a schematic placement
                    boolean inPlacement = false;
                    for (PlacementBounds b : bounds) {
                        if (b.contains(pos)) {
                            inPlacement = true;
                            break;
                        }
                    }
                    if (!inPlacement) continue;

                    BlockState worldState = mc.world.getBlockState(pos);
                    BlockState schematicState;
                    try {
                        schematicState = schematicWorld.getBlockState(pos);
                    } catch (Exception e) {
                        continue;
                    }

                    // Check: schematic wants waterlogging, world block matches, world NOT waterlogged
                    if (schematicState.contains(Properties.WATERLOGGED)
                            && schematicState.get(Properties.WATERLOGGED)
                            && worldState.getBlock().equals(schematicState.getBlock())
                            && (!worldState.contains(Properties.WATERLOGGED) || !worldState.get(Properties.WATERLOGGED))) {
                        result.add(pos.toImmutable());
                    }
                }
            }
        }

        result.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(playerPos)));
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
     * Swaps the item in {@code inventorySlot} (PlayerInventory.main index 9-35)
     * with the item in {@code hotbarSlot} (PlayerInventory.main index 0-8).
     *
     * PlayerScreenHandler slot layout:
     *   0=crafter output, 1-4=craft grid, 5-8=armor,
     *   9-35=main inventory rows, 36-44=hotbar, 45=offhand.
     * PlayerInventory.main indices 0-8 map to screen slots 36-44.
     * PlayerInventory.main indices 9-35 map to screen slots 9-35 (same).
     */
    private static void swapSlotWithHotbar(MinecraftClient mc, int inventorySlot, int hotbarSlot) {
        int syncId = mc.player.playerScreenHandler.syncId;
        int screenInvSlot = inventorySlot;          // main[9-35] → screen 9-35
        int screenHotbarSlot = 36 + hotbarSlot;      // main[0-8]  → screen 36-44
        mc.interactionManager.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, screenHotbarSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, mc.player);
    }
}
