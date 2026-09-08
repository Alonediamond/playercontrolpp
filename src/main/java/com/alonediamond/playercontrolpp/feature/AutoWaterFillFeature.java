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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 给「投影里该含水、世界里还没含水」的方块填水。
 *
 * <pre>
 * SCANNING -&gt; FINDING_BUCKET -&gt; [SHULKERING] -&gt; ROTATING -&gt; PLACING_WATER -&gt; COOLDOWN -&gt; SCANNING
 * </pre>
 *
 * <p>只有散装水桶用完、物品栏里有潜影盒装着水桶、且装了 QuickShulker 能就地开盒时，
 * 才会进入 SHULKERING。
 *
 * <p>范围内没有要填的方块时进入 AUTO_STOP_COUNTDOWN，三秒内以较低频率继续扫，
 * 走到下一处会自动接着干，不必再按一次热键。
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

    /** 自动停止的宽限期：20 tps 下 3 秒。 */
    private static final int AUTO_STOP_TICKS = 60;
    /** 倒计时期间的重扇间隔（否则就是每 tick 扫一遍 11³ 的立方体）。 */
    private static final int COUNTDOWN_SCAN_INTERVAL = 5;
    /** 等 QuickShulker 打开的容器界面，超过这么多 tick 就放弃。 */
    private static final int SHULKER_OPEN_WAIT_TICKS = 20;
    /** 从潜影盒里掏水桶最多试几次，超了就停。 */
    private static final int MAX_SHULKER_ATTEMPTS = 3;
    /** 瞄准时每 tick 最大转动角度。 */
    private static final float MAX_TURN_STEP = 20.0f;
    /** 点击前允许的瞄准误差（度）。 */
    private static final float AIM_YAW_TOLERANCE = 2.0f;
    private static final float AIM_PITCH_TOLERANCE = 1.0f;
    /**
     * 一个方块留在「刚试过」名单里的时长。服务端要几 tick 才把新的含水状态回传，
     * 没有这个冷却，下一次扫描会又选中同一个方块再右键一次。
     */
    private static final int RETRY_BLOCK_COOLDOWN = 20;

    private static boolean enabled;
    private static State state = State.SCANNING;
    private static int stateTimer;
    private static int autoStopCountdown;
    private static int tickCounter;
    private static int shulkerAttempts;
    private static BlockPos currentTarget;
    /** 坐标 -&gt; 它重新成为候选的 tick。 */
    private static final Map<BlockPos, Integer> recentlyAttempted = new HashMap<>();
    private static final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();

    /** 注册进 {@link FeatureRegistry}，见 {@code InitHandler}。 */
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
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // 安全阀：潜行时暂停，玩家随时能夺回控制权。
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

    // ---- SCANNING ----

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

    // ---- FINDING_BUCKET ----

    private static void tickFindingBucket(Minecraft mc) {
        Inventory inv = mc.player.getInventory();

        // 1) 快捷栏里就有：直接选中。
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            if (isWaterBucket(inv.getItem(i))) {
                selectHotbarSlot(mc, i);
                shulkerAttempts = 0;
                state = State.ROTATING;
                return;
            }
        }

        // 2) 在主背包里：换到快捷栏。
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

        // 3) 最后一招：潜影盒里的水桶，用 QuickShulker 就地开盒取出。
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

        // 先计次再去试，而不是成功后才计。倒计时状态每次重新找到目标都会重置计时器，
        // 一直失败的尝试若不先计次，两个状态会无限来回弹，把 ActionBar 刷爆。
        shulkerAttempts++;

        // QuickShulker 的槽位参数是容器界面索引，不是物品栏索引。
        int screenSlot = shulkerSlot < PlayerUtil.HOTBAR_SIZE
                ? InventoryMenu.USE_ROW_SLOT_START + shulkerSlot
                : shulkerSlot;
        if (!quickShulker.openShulkerBox(screenSlot)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            beginAutoStopCountdown();
            return;
        }
        stateTimer = SHULKER_OPEN_WAIT_TICKS;
        state = State.SHULKERING;
    }

    /** @return 一个空的快捷栏格；快捷栏满了则返回当前选中格。 */
    private static int firstFreeHotbarSlot(Inventory inv) {
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return InventoryCompat.getSelectedSlot(inv);
    }

    /** @return 装着水桶的潜影盒所在的物品栏索引；没有则 -1。 */
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
     * 选中一个快捷栏格并告知服务端。
     *
     * <p>只改选中槽位只影响客户端；不发 ServerboundSetCarriedItemPacket 的话服务端仍认为
     * 玩家手持之前那个物品，{@code useItemOn} 会静默失败。
     */
    private static void selectHotbarSlot(Minecraft mc, int slot) {
        InventoryCompat.setSelectedSlot(mc.player.getInventory(), slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }

    // ---- SHULKERING ----

    /**
     * 等 QuickShulker 开出的界面，只取一个水桶，关掉，再回 FINDING_BUCKET
     * 让水桶最终被选到手上。
     */
    private static void tickShulkering(Minecraft mc) {
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            // 包往返要几 tick，等满了才算失败。
            if (--stateTimer <= 0) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                beginAutoStopCountdown();
            }
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;
        boolean tookOne = false;
        for (Slot slot : handler.slots) {
            // 跳过界面里玩家背包那一半，只要盒子自己的槽位。
            if (slot.container == mc.player.getInventory()) continue;
            if (!isWaterBucket(slot.getItem())) continue;
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            tookOne = true;
            break; // 一个水桶就够，早先的实现会把整盒描空
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

    // ---- ROTATING ----

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

    // ---- PLACING_WATER ----

    private static void tickPlacingWater(Minecraft mc) {
        if (currentTarget == null || mc.gameMode == null) {
            state = State.SCANNING;
            return;
        }

        // 桶空了或手上被换成别的：保留目标，重新装备。
        if (!isWaterBucket(mc.player.getMainHandItem())) {
            state = State.FINDING_BUCKET;
            return;
        }

        BlockState worldState = mc.level.getBlockState(currentTarget);
        if (isWaterlogged(worldState)) {
            abandonTarget();
            return;
        }
        BlockGetter schematicWorld = LitematicaIntegration.getInstance().getSchematicWorld();
        if (schematicWorld != null && !schematicWantsWaterAt(schematicWorld, currentTarget, worldState)) {
            abandonTarget();
            return;
        }

        // 两个调用都要：useItemOn 发出针对方块的交互包，useItem 才让服务端走
        // BucketItem 那条真正含水的分支。
        Vec3 center = Vec3.atCenterOf(currentTarget);
        BlockHitResult hitResult = new BlockHitResult(center, Direction.UP, currentTarget, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

        markAttempted(currentTarget);
        currentTarget = null;
        state = State.COOLDOWN;
        stateTimer = Configs.Settings.WATER_FILL_OPERATION_DELAY.getIntegerValue();
    }

    /** 放弃当前目标（仍记入冷却，免得下一趟又选它）。 */
    private static void abandonTarget() {
        if (currentTarget != null) {
            markAttempted(currentTarget);
            currentTarget = null;
        }
        state = State.SCANNING;
    }

    // ---- COOLDOWN ----

    private static void tickCooldown(Minecraft mc) {
        if (--stateTimer <= 0) {
            state = State.SCANNING;
        }
    }

    // ---- AUTO_STOP_COUNTDOWN ----

    private static void tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        // 定期重扫而不是每 tick 扫：扫描是最贵的一步，而玩家四分之一秒走不了多远。
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

    // ---- 扫描 ----

    /**
     * 在受手长限制的立方体里找最近的「投影要含水、世界还没含水」的方块。
     *
     * <p>单趟扫描 + 复用一个 {@link BlockPos.MutableBlockPos}：早先的实现每个候选分配一个
     * BlockPos、把命中全收进列表再排序，而结果只读第 0 个。
     *
     * @return 最近的候选；没有则 {@code null}
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
                        continue; // 超出投影自身范围
                    }
                    if (!isWaterlogged(schematicState)) continue;

                    BlockState worldState = mc.level.getBlockState(cursor);
                    if (worldState.getBlock() != schematicState.getBlock()) continue;
                    if (isWaterlogged(worldState)) continue;

                    best = cursor.immutable(); // 必须复制：游标还要继续动
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
        return LitematicaIntegration.getInstance().getSchematicWorld();
    }

    /**
     * 点击前再跟投影核对一次：{@code pos} 确实该含水，且世界里那个方块就是投影期望的方块。
     * 必须重查——扫描结果到这时可能已经过期几 tick。
     */
    private static boolean schematicWantsWaterAt(BlockGetter schematicWorld, BlockPos pos, BlockState worldState) {
        BlockState schemState = schematicWorld.getBlockState(pos);
        return worldState.getBlock() == schemState.getBlock() && isWaterlogged(schemState);
    }

    // ---- 重试冷却记账 ----

    private static void markAttempted(BlockPos pos) {
        recentlyAttempted.put(pos.immutable(), tickCounter + RETRY_BLOCK_COOLDOWN);
    }

    private static void expireRetryCooldowns() {
        if (recentlyAttempted.isEmpty()) return;
        recentlyAttempted.values().removeIf(expiry -> expiry <= tickCounter);
    }

    // ---- 杂项 ----

    private static boolean isWaterBucket(ItemStack stack) {
        return ItemUtil.is(stack, Items.WATER_BUCKET);
    }

    /**
     * 把 {@code inventorySlot}（物品栏索引 9-35）与 {@code hotbarSlot}（0-8）的物品互换，
     * 走三次容器点击，让服务端跟着换。
     */
    private static void swapSlotWithHotbar(Minecraft mc, int inventorySlot, int hotbarSlot) {
        int syncId = mc.player.inventoryMenu.containerId;
        // 物品栏索引和界面槽位索引不是同一套编号：
        //   主背包[9-35] -> 界面 9-35，快捷栏[0-8] -> 界面 36-44。
        int screenInvSlot = inventorySlot;
        int screenHotbarSlot = InventoryMenu.USE_ROW_SLOT_START + hotbarSlot;
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
        SlotActionCompat.pickup(mc, syncId, screenHotbarSlot);
        SlotActionCompat.pickup(mc, syncId, screenInvSlot);
    }
}
