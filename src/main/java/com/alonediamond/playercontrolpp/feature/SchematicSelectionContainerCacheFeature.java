package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.SelectionBounds;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 缓存投影选区容器功能：扫描 Litematica 投影选区内的所有容器，在玩家手长范围内自动打开并缓存到箱子追踪模组。
 *
 * <p>该功能不依赖自定义网络包，仅在玩家手长范围内打开容器，超出范围会提示玩家靠近。
 * 缓存过程采用增量扫描，每 tick 扫描一部分方块，找到容器后按距离排序依次缓存。
 */
public final class SchematicSelectionContainerCacheFeature {

    private enum State {
        IDLE,                   // 空闲
        SCANNING,               // 扫描投影选区中的容器
        READY,                  // 准备打开下一个容器
        WAITING_PLAYER_APPROACH,// 等待玩家靠近容器
        OPENING_CONTAINER,      // 正在打开容器
        WAITING_AFTER_OPEN,     // 容器已打开，等待箱子追踪缓存
        CLOSING_GUI,            // 关闭容器界面
        COOLDOWN                // 冷却延迟
    }

    private static final int SCAN_BLOCKS_PER_TICK = 4096;
    private static final int OPEN_WAIT_TICKS = 10;
    private static final int RECORD_WAIT_TICKS = 1;
    private static final int CLOSE_SETTLE_TICKS = 2;
    private static final int APPROACH_WAIT_TICKS = 60; // 3 seconds at 20 tps

    private static final List<SelectionBounds> selectionBoxes = new ArrayList<>();
    private static final LinkedHashSet<BlockPos> scannedContainers = new LinkedHashSet<>();
    private static final Set<BlockPos> claimedContainerParts = new HashSet<>();
    private static final ArrayDeque<BlockPos> queue = new ArrayDeque<>();

    private static State state = State.IDLE;
    private static boolean active;
    private static int boxIndex;
    private static int scanX;
    private static int scanY;
    private static int scanZ;
    private static boolean scanCursorReady;
    private static BlockPos currentTarget;
    private static int stateTimer;
    private static int totalContainers;
    private static int cachedContainers;
    private static int failedContainers;
    private static int outOfRangeCount;

    public static final ClientFeature FEATURE = new ClientFeature() {
        @Override public void onClientTick(Minecraft mc) { tick(mc); }
        @Override public void onWorldChange() { resetForWorldChange(); }
        @Override public boolean isActive() { return active; }
    };

    private SchematicSelectionContainerCacheFeature() {}

    public static boolean isActive() {
        return active;
    }

    public static void startOrCancel(Minecraft client) {
        if (active) {
            cancel(client, true);
            return;
        }

        LitematicaIntegration litematica = LitematicaIntegration.getInstance();
        ChestTrackerIntegration chestTracker = ChestTrackerIntegration.getInstance();
        if (!litematica.isLoaded() || !chestTracker.isLoaded()) {
            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.mods_missing");
            return;
        }
        if (!chestTracker.hasLoadedMemoryBank()) {
            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.no_memory_bank");
            return;
        }
        if (client.player == null || client.level == null
                || client.player.containerMenu != client.player.inventoryMenu
                || ScreenCompat.getScreen(client) != null
                || AutoCacheNearbyContainersFeature.isEnabled()) {
            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.busy");
            return;
        }

        List<SelectionBounds> boxes = litematica.getCurrentSelectionBounds();
        if (boxes.isEmpty()) {
            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.no_selection");
            return;
        }

        clearTaskState();
        selectionBoxes.addAll(boxes);
        active = true;
        state = State.SCANNING;
        MessageUtil.sendActionBar(client,
                "playercontrolpp.message.schematic_cache.scanning");
    }

    private static void tick(Minecraft client) {
        if (!active || client.player == null || client.level == null) return;

        // 尊重其他界面：玩家打开其他GUI时暂停交互
        if (ScreenCompat.getScreen(client) != null
                && state != State.SCANNING
                && state != State.READY
                && state != State.WAITING_PLAYER_APPROACH
                && state != State.COOLDOWN
                && !(ScreenCompat.getScreen(client) instanceof AbstractContainerScreen)) {
            return;
        }

        switch (state) {
            case SCANNING -> scanTick(client);
            case READY -> requestNext(client);
            case WAITING_PLAYER_APPROACH -> tickWaitingPlayerApproach(client);
            case OPENING_CONTAINER -> tickOpeningContainer(client);
            case WAITING_AFTER_OPEN -> tickWaitingAfterOpen(client);
            case CLOSING_GUI -> tickClosingGui(client);
            case COOLDOWN -> {
                if (--stateTimer <= 0) state = State.READY;
            }
            case IDLE -> {}
        }
    }

    private static void scanTick(Minecraft client) {
        for (int count = 0; count < SCAN_BLOCKS_PER_TICK; count++) {
            BlockPos position = nextScanPosition();
            if (position == null) {
                finishScan(client);
                return;
            }
            // Check if chunk is loaded
            if (!client.level.isLoaded(position)) continue;

            BlockState blockState = client.level.getBlockState(position);
            if (!AutoCacheNearbyContainersFeature.isWhitelistedContainer(blockState.getBlock())) {
                continue;
            }
            addContainerCandidate(position, blockState);
        }
    }

    private static BlockPos nextScanPosition() {
        while (boxIndex < selectionBoxes.size()) {
            SelectionBounds box = selectionBoxes.get(boxIndex);
            if (!scanCursorReady) {
                scanX = box.min().getX();
                scanY = box.min().getY();
                scanZ = box.min().getZ();
                scanCursorReady = true;
            }

            BlockPos result = new BlockPos(scanX, scanY, scanZ);
            scanZ++;
            if (scanZ > box.max().getZ()) {
                scanZ = box.min().getZ();
                scanY++;
                if (scanY > box.max().getY()) {
                    scanY = box.min().getY();
                    scanX++;
                    if (scanX > box.max().getX()) {
                        boxIndex++;
                        scanCursorReady = false;
                    }
                }
            }
            return result;
        }
        return null;
    }

    private static void addContainerCandidate(BlockPos position, BlockState stateAtPosition) {
        BlockPos immutable = position.immutable();
        if (claimedContainerParts.contains(immutable)) return;

        claimedContainerParts.add(immutable);
        if (stateAtPosition.getBlock() instanceof ChestBlock
                && stateAtPosition.hasProperty(ChestBlock.TYPE)
                && stateAtPosition.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            claimedContainerParts.add(immutable.relative(
                    ChestBlock.getConnectedDirection(stateAtPosition)));
        }
        scannedContainers.add(immutable);
    }

    private static void finishScan(Minecraft client) {
        if (scannedContainers.isEmpty()) {
            finish(client, "playercontrolpp.message.schematic_cache.none_found");
            return;
        }

        List<BlockPos> sorted = new ArrayList<>(scannedContainers);
        BlockPos playerPosition = client.player.blockPosition();
        sorted.sort(Comparator.comparingDouble(pos -> pos.distSqr(playerPosition)));
        queue.addAll(sorted);
        totalContainers = queue.size();
        state = State.READY;
        MessageUtil.sendActionBar(client,
                "playercontrolpp.message.schematic_cache.found", totalContainers);
    }

    private static void requestNext(Minecraft client) {
        if (queue.isEmpty()) {
            finish(client, "playercontrolpp.message.schematic_cache.completed",
                    cachedContainers, failedContainers);
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu
                || ScreenCompat.getScreen(client) != null) {
            return;
        }

        currentTarget = queue.peekFirst();

        // 检查容器是否在手长范围内
        double reachSq = PlayerUtil.blockReachSq(client.player);
        double distanceSq = client.player.position().distanceToSqr(Vec3.atCenterOf(currentTarget));

        if (distanceSq > reachSq) {
            // 超出范围，等待玩家靠近
            if (outOfRangeCount == 0) {
                MessageUtil.sendActionBar(client,
                        "playercontrolpp.message.schematic_cache.waiting_approach");
            }
            outOfRangeCount++;
            state = State.WAITING_PLAYER_APPROACH;
            stateTimer = APPROACH_WAIT_TICKS;
            return;
        }

        outOfRangeCount = 0;
        stateTimer = OPEN_WAIT_TICKS;
        state = State.OPENING_CONTAINER;

        // 尝试打开容器
        tryOpenContainer(client, currentTarget);
    }

    private static void tickWaitingPlayerApproach(Minecraft client) {
        if (currentTarget == null) {
            state = State.READY;
            return;
        }

        // 每 tick 检查玩家是否已经靠近
        double reachSq = PlayerUtil.blockReachSq(client.player);
        double distanceSq = client.player.position().distanceToSqr(Vec3.atCenterOf(currentTarget));

        if (distanceSq <= reachSq) {
            // 玩家已靠近，尝试打开容器
            outOfRangeCount = 0;
            stateTimer = OPEN_WAIT_TICKS;
            state = State.OPENING_CONTAINER;
            tryOpenContainer(client, currentTarget);
            return;
        }

        // 等待超时
        if (--stateTimer <= 0) {
            // 如果连续多个容器都超出范围，说明玩家可能需要移动
            if (outOfRangeCount >= 5) {
                finish(client, "playercontrolpp.message.schematic_cache.too_far");
                return;
            }

            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.skip_out_of_range");
            completeCurrent(client, false);
        }
    }

    private static void tryOpenContainer(Minecraft client, BlockPos position) {
        if (client.player == null || client.level == null) return;

        BlockState state = client.level.getBlockState(position);
        if (state.isAir()) {
            completeCurrent(client, false);
            return;
        }

        // 计算最靠近玩家的面
        Vec3 playerPos = client.player.getEyePosition(1.0F);
        Vec3 blockCenter = Vec3.atCenterOf(position);
        Vec3 diff = blockCenter.subtract(playerPos);

        Direction facing;
        if (Math.abs(diff.x) > Math.abs(diff.y) && Math.abs(diff.x) > Math.abs(diff.z)) {
            facing = diff.x > 0 ? Direction.WEST : Direction.EAST;
        } else if (Math.abs(diff.y) > Math.abs(diff.z)) {
            facing = diff.y > 0 ? Direction.DOWN : Direction.UP;
        } else {
            facing = diff.z > 0 ? Direction.NORTH : Direction.SOUTH;
        }

        BlockHitResult hitResult = new BlockHitResult(
                blockCenter, facing, position, false);

        client.gameMode.useItemOn(
                client.player,
                InteractionHand.MAIN_HAND,
                hitResult);
    }

    private static void tickOpeningContainer(Minecraft client) {
        if (--stateTimer <= 0) {
            // 超时，未能打开容器
            completeCurrent(client, false);
            return;
        }

        // 检查是否成功打开了容器
        if (client.player.containerMenu != client.player.inventoryMenu
                && ScreenCompat.getScreen(client) instanceof AbstractContainerScreen) {
            state = State.WAITING_AFTER_OPEN;
            stateTimer = RECORD_WAIT_TICKS;
        }
    }

    private static void tickWaitingAfterOpen(Minecraft client) {
        if (--stateTimer <= 0) {
            // 等待箱子追踪记录完成后保存
            boolean saved = saveContainerToChestTracker(client);
            closeGuiIfOpen(client);
            completeCurrent(client, saved);
        }
    }

    private static void tickClosingGui(Minecraft client) {
        if (--stateTimer <= 0) {
            int delay = Configs.Settings.CACHE_DELAY.getIntegerValue();
            if (delay > 0) {
                state = State.COOLDOWN;
                stateTimer = delay;
            } else {
                state = State.READY;
            }
        }
    }

    private static boolean saveContainerToChestTracker(Minecraft client) {
        if (client.player == null || client.level == null || currentTarget == null) {
            return false;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) {
            return false;
        }

        BlockState state = client.level.getBlockState(currentTarget);
        return ChestTrackerIntegration.getInstance().cacheContainer(
                client.level, currentTarget, state, menu);
    }

    private static void completeCurrent(Minecraft client, boolean saved) {
        if (currentTarget != null && currentTarget.equals(queue.peekFirst())) {
            queue.removeFirst();
        }
        if (saved) cachedContainers++; else failedContainers++;
        currentTarget = null;

        MessageUtil.sendActionBar(client,
                "playercontrolpp.message.schematic_cache.progress",
                queue.size(), cachedContainers, failedContainers);

        state = State.CLOSING_GUI;
        stateTimer = CLOSE_SETTLE_TICKS;
    }

    private static void closeGuiIfOpen(Minecraft client) {
        if (client.player != null
                && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        ChestTrackerIntegration.getInstance().clearInteractionTracker();
    }

    private static void cancel(Minecraft client, boolean notify) {
        closeGuiIfOpen(client);
        clearTaskState();
        if (notify) {
            MessageUtil.sendActionBar(client,
                    "playercontrolpp.message.schematic_cache.cancelled");
        }
    }

    private static void resetForWorldChange() {
        if (active) cancel(Minecraft.getInstance(), false);
    }

    private static void finish(Minecraft client, String translationKey, Object... args) {
        clearTaskState();
        MessageUtil.sendActionBar(client, translationKey, args);
    }

    private static void clearTaskState() {
        active = false;
        state = State.IDLE;
        selectionBoxes.clear();
        scannedContainers.clear();
        claimedContainerParts.clear();
        queue.clear();
        boxIndex = 0;
        scanCursorReady = false;
        currentTarget = null;
        stateTimer = 0;
        totalContainers = 0;
        cachedContainers = 0;
        failedContainers = 0;
        outOfRangeCount = 0;
    }
}
