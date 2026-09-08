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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缓存投影选区容器：把 Litematica 当前区域选区内的容器逐个打开，写进箱子追踪的缓存库。
 *
 * <p>取材策略与「自动缓存附近容器」一致——只缓存玩家<b>此刻伸手可及</b>的容器，走到哪缓存到哪；
 * 区别仅在于候选集合被限制在选区内。因此不存在"排队等玩家走到队首那个容器"的情况。
 *
 * <p>两点让它在大选区下依然跟手：
 * <ul>
 *   <li>扫描按 16³ 区段推进，区段按距玩家远近排序，扫到的容器立刻可被缓存，不必等整个选区扫完；</li>
 *   <li>待缓存容器按区段分桶，每 tick 只在玩家周围几个桶里找最近目标，选区里上千个容器也不影响。</li>
 * </ul>
 *
 * <pre>SEEKING → OPENING → [COOLDOWN] → SEEKING</pre>
 * 无事可做时停在 SEEKING 等玩家移动，不会自行终止（按热键取消）。
 */
public final class SchematicSelectionContainerCacheFeature {

    private enum State {
        IDLE,       // 未运行
        SEEKING,    // 找一个够得着的未缓存容器
        OPENING,    // 右键已发出，等容器界面与内容包
        COOLDOWN    // 配置的容器间隔
    }

    /** 每 tick 的扫描方块预算。 */
    private static final int SCAN_BLOCKS_PER_TICK = 16384;
    /** 发出右键后等待容器界面的最长 tick 数。 */
    private static final int OPEN_WAIT_TICKS = 10;
    /** 界面已开但内容包还没到时，最多再宽限的 tick 数。 */
    private static final int CONTENT_WAIT_TICKS = 2;
    /** 单 tick 内最多推进几步状态；纯粹是防御性上限，正常只会走 1~2 步。 */
    private static final int MAX_STEPS_PER_TICK = 8;
    /** 玩家移动超过 16 格就重排剩余区段的扫描顺序。 */
    private static final double RESORT_DISTANCE_SQ = 256.0;
    /** 两次重排之间的最小间隔 tick。 */
    private static final int RESORT_INTERVAL = 20;
    /** 提示信息的最小间隔 tick，避免刷屏。 */
    private static final int MESSAGE_INTERVAL = 20;
    /** 未加载区段重新入队的间隔 tick。 */
    private static final int DEFERRED_RETRY_TICKS = 40;

    /** 待扫描的一个区段（已裁剪到选区内的闭区间）。 */
    private record ScanSection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double distSqTo(double x, double y, double z) {
            double cx = (minX + maxX + 1) * 0.5;
            double cy = (minY + maxY + 1) * 0.5;
            double cz = (minZ + maxZ + 1) * 0.5;
            return (cx - x) * (cx - x) + (cy - y) * (cy - y) + (cz - z) * (cz - z);
        }
    }

    /** 待缓存容器，按 16³ 区段分桶：最近查询只需看玩家周围的几个桶。 */
    private static final Map<Long, List<BlockPos>> pendingBySection = new HashMap<>();
    /** 已归属于某个双箱、不再单独算一个容器的坐标。 */
    private static final Set<BlockPos> claimed = new HashSet<>();
    /** 待扫描区段，按距玩家由近到远排列。 */
    private static final List<ScanSection> scanQueue = new ArrayList<>();
    /** 区块未加载而跳过的区段，稍后重试。 */
    private static final List<ScanSection> deferredSections = new ArrayList<>();

    private static State state = State.IDLE;
    private static boolean active;

    // 扫描游标：scanQueue 中第几个区段，以及区段内的 x/y/z 位置
    private static int scanCursor;
    private static int cursorX;
    private static int cursorY;
    private static int cursorZ;
    private static boolean cursorReady;
    private static int totalSections;
    private static Vec3 lastSortPos = Vec3.ZERO;
    private static int resortTimer;
    private static int deferredRetryTimer;

    private static BlockPos currentTarget;
    private static int stateTimer;
    private static int contentTimer;
    private static int pendingCount;
    private static int cachedCount;
    private static int failedCount;
    private static int messageTimer;

    public static final ClientFeature FEATURE = new ClientFeature() {
        @Override public void onClientTick(Minecraft mc) { tick(mc); }
        @Override public void onWorldChange() { if (active) cancel(Minecraft.getInstance(), false); }
        @Override public boolean isActive() { return active; }
    };

    private SchematicSelectionContainerCacheFeature() {}

    public static boolean isActive() {
        return active;
    }

    // ---- 启动 / 取消 ----

    public static void startOrCancel(Minecraft client) {
        if (active) {
            cancel(client, true);
            return;
        }

        LitematicaIntegration litematica = LitematicaIntegration.getInstance();
        ChestTrackerIntegration chestTracker = ChestTrackerIntegration.getInstance();
        if (!litematica.isLoaded() || !chestTracker.isLoaded()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.mods_missing");
            return;
        }
        if (!chestTracker.hasLoadedMemoryBank()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.no_memory_bank");
            return;
        }
        if (client.player == null || client.level == null
                || client.player.containerMenu != client.player.inventoryMenu
                || ScreenCompat.getScreen(client) != null
                || AutoCacheNearbyContainersFeature.isEnabled()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.busy");
            return;
        }

        List<SelectionBounds> boxes = litematica.getCurrentSelectionBounds();
        if (boxes.isEmpty()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.no_selection");
            return;
        }

        clearTaskState();
        buildScanQueue(boxes, client.player.position());
        active = true;
        state = State.SEEKING;
        MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.scanning");
    }

    private static void cancel(Minecraft client, boolean notify) {
        closeGuiIfOpen(client);
        clearTaskState();
        if (notify) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.cancelled");
        }
    }

    private static void finish(Minecraft client, String translationKey, Object... args) {
        closeGuiIfOpen(client);
        clearTaskState();
        MessageUtil.sendActionBar(client, translationKey, args);
    }

    private static void clearTaskState() {
        active = false;
        state = State.IDLE;
        pendingBySection.clear();
        claimed.clear();
        scanQueue.clear();
        deferredSections.clear();
        scanCursor = 0;
        cursorReady = false;
        totalSections = 0;
        lastSortPos = Vec3.ZERO;
        resortTimer = 0;
        deferredRetryTimer = 0;
        currentTarget = null;
        stateTimer = 0;
        contentTimer = 0;
        pendingCount = 0;
        cachedCount = 0;
        failedCount = 0;
        messageTimer = 0;
        // 状态清零时同步撤掉 GUI 屏蔽；功能重启后下一 tick 会立刻续租。
        ContainerGuiSuppressor.expire();
    }

    // ---- 主循环 ----

    private static void tick(Minecraft client) {
        if (!active || client.player == null || client.level == null) return;

        // 功能活跃期间维持 GUI 屏蔽租约（每 tick 续，停止后一个租约期内自愈）。
        ContainerGuiSuppressor.renew();

        // 玩家开着别的界面（非容器界面）时暂停交互，但扫描可以继续。
        boolean otherScreenOpen = ScreenCompat.getScreen(client) != null
                && !(ScreenCompat.getScreen(client) instanceof AbstractContainerScreen);

        if (messageTimer > 0) messageTimer--;
        if (resortTimer > 0) resortTimer--;

        scanStep(client);

        if (otherScreenOpen) return;

        // 没有等待外部事件的状态在同一 tick 内连续推进：关箱与开下一个箱之间
        // 不再各自浪费一个 tick。
        for (int step = 0; step < MAX_STEPS_PER_TICK && advance(client); step++) {
            // advance() 返回 true 表示这一步没有消耗服务端往返，可以立刻继续。
        }
    }

    /**
     * 推进一步状态机。
     *
     * @return {@code true} 表示这一步没有等待服务端，可以在同一 tick 内继续推进。
     */
    private static boolean advance(Minecraft client) {
        switch (state) {
            case SEEKING -> {
                return seek(client);
            }
            case OPENING -> {
                return tickOpening(client);
            }
            case COOLDOWN -> {
                if (--stateTimer <= 0) {
                    state = State.SEEKING;
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean seek(Minecraft client) {
        // 上一个容器界面还没关干净时先关掉，否则右键会落到旧界面上。
        if (client.player.containerMenu != client.player.inventoryMenu) {
            closeGuiIfOpen(client);
            return false;
        }

        BlockPos target = takeNearestReachable(client);
        if (target != null) {
            currentTarget = target;
            stateTimer = OPEN_WAIT_TICKS;
            contentTimer = CONTENT_WAIT_TICKS;
            state = State.OPENING;
            sendOpenClick(client, target);
            return false; // 等服务端回包
        }

        // 够不着任何容器：扫描完 + 队列清空才算做完，否则原地等玩家移动。
        if (pendingCount == 0 && scanQueue.isEmpty()) {
            if (deferredSections.isEmpty()) {
                if (cachedCount == 0 && failedCount == 0) {
                    finish(client, "playercontrolpp.message.schematic_cache.none_found");
                } else {
                    finish(client, "playercontrolpp.message.schematic_cache.completed",
                            cachedCount, failedCount);
                }
            } else {
                notify(client, "playercontrolpp.message.schematic_cache.chunks_unloaded",
                        deferredSections.size());
            }
        } else if (pendingCount > 0) {
            notify(client, "playercontrolpp.message.schematic_cache.waiting_move", pendingCount);
        }
        return false;
    }

    private static boolean tickOpening(Minecraft client) {
        // 界面被 ContainerGuiSuppressor 吞掉时 Screen 恒为 null，判据只用菜单状态。
        boolean menuOpen = client.player.containerMenu != client.player.inventoryMenu;

        if (menuOpen) {
            // 界面已开，但物品内容是另一个包。等到看见东西、或宽限用完再落库，
            // 避免把还没同步的容器记成空容器。
            if (!menuHasContent(client) && --contentTimer > 0) {
                return false;
            }
            boolean saved = saveContainerToChestTracker(client);
            closeGuiIfOpen(client);
            complete(client, saved);
            return state == State.SEEKING; // 延迟为 0 时同一 tick 直接开下一个
        }

        if (--stateTimer <= 0) {
            complete(client, false);
            return true; // 没开成，也没消耗往返，立刻找下一个
        }
        return false;
    }

    /** 当前目标处理结束：记账、发进度、按配置决定是否进入冷却。 */
    private static void complete(Minecraft client, boolean saved) {
        if (saved) cachedCount++; else failedCount++;
        currentTarget = null;

        notify(client, "playercontrolpp.message.schematic_cache.progress",
                pendingCount, cachedCount, failedCount);

        int delay = Configs.Settings.CACHE_DELAY.getIntegerValue();
        if (delay > 0) {
            stateTimer = delay;
            state = State.COOLDOWN;
        } else {
            state = State.SEEKING;
        }
    }

    // ---- 选区扫描 ----

    /** 把选区切成区段，按距玩家远近排队。 */
    private static void buildScanQueue(List<SelectionBounds> boxes, Vec3 playerPos) {
        for (SelectionBounds box : boxes) {
            BlockPos min = box.min();
            BlockPos max = box.max();
            for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
                for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                    for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                        scanQueue.add(new ScanSection(
                                Math.max(min.getX(), sx << 4), Math.max(min.getY(), sy << 4),
                                Math.max(min.getZ(), sz << 4),
                                Math.min(max.getX(), (sx << 4) + 15),
                                Math.min(max.getY(), (sy << 4) + 15),
                                Math.min(max.getZ(), (sz << 4) + 15)));
                    }
                }
            }
        }
        totalSections = scanQueue.size();
        lastSortPos = playerPos;
        sortRemainingSections(playerPos, 0);
    }

    /** 按距离重排还没扫的区段，让玩家附近的先被扫到。 */
    private static void sortRemainingSections(Vec3 from, int fromIndex) {
        if (fromIndex >= scanQueue.size()) return;
        scanQueue.subList(fromIndex, scanQueue.size())
                .sort(Comparator.comparingDouble(s -> s.distSqTo(from.x, from.y, from.z)));
    }

    private static void scanStep(Minecraft client) {
        if (scanQueue.isEmpty()) {
            retryDeferredSections(client);
            return;
        }

        Vec3 playerPos = client.player.position();
        if (resortTimer == 0 && playerPos.distanceToSqr(lastSortPos) > RESORT_DISTANCE_SQ) {
            lastSortPos = playerPos;
            resortTimer = RESORT_INTERVAL;
            // 从下一个区段开始重排，当前区段扫到一半，挪走会漏方块。
            sortRemainingSections(playerPos, scanCursor + 1);
        }

        Set<Block> whitelist = AutoCacheNearbyContainersFeature.whitelistedBlocks();
        if (whitelist.isEmpty()) {
            scanQueue.clear();
            scanCursor = 0;
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int budget = SCAN_BLOCKS_PER_TICK;

        while (budget > 0 && scanCursor < scanQueue.size()) {
            ScanSection section = scanQueue.get(scanCursor);

            if (!cursorReady) {
                // 区块未加载就整段跳过，稍后重试——按方块查会白算 4096 次。
                if (!client.level.isLoaded(new BlockPos(section.minX(), section.minY(), section.minZ()))) {
                    deferredSections.add(section);
                    advanceSection();
                    continue;
                }
                cursorX = section.minX();
                cursorY = section.minY();
                cursorZ = section.minZ();
                cursorReady = true;
            }

            cursor.set(cursorX, cursorY, cursorZ);
            BlockState blockState = client.level.getBlockState(cursor);
            if (whitelist.contains(blockState.getBlock())) {
                addContainerCandidate(cursor, blockState);
            }
            budget--;

            if (++cursorZ > section.maxZ()) {
                cursorZ = section.minZ();
                if (++cursorY > section.maxY()) {
                    cursorY = section.minY();
                    if (++cursorX > section.maxX()) {
                        advanceSection();
                    }
                }
            }
        }

        if (scanCursor >= scanQueue.size() && !scanQueue.isEmpty()) {
            scanQueue.clear();
            scanCursor = 0;
            notify(client, "playercontrolpp.message.schematic_cache.found", pendingCount);
        }
    }

    private static void advanceSection() {
        scanCursor++;
        cursorReady = false;
    }

    /** 主队列扫完后，隔一段时间把未加载的区段放回去再试一次（玩家走近就能扫到）。 */
    private static void retryDeferredSections(Minecraft client) {
        if (deferredSections.isEmpty()) return;
        if (--deferredRetryTimer > 0) return;

        deferredRetryTimer = DEFERRED_RETRY_TICKS;
        scanQueue.addAll(deferredSections);
        deferredSections.clear();
        scanCursor = 0;
        cursorReady = false;
        sortRemainingSections(client.player.position(), 0);
    }

    /** 把一个容器登记进分桶索引；双箱只登记一半。 */
    private static void addContainerCandidate(BlockPos position, BlockState stateAtPosition) {
        BlockPos immutable = position.immutable();
        if (claimed.contains(immutable)) return;

        claimed.add(immutable);
        if (stateAtPosition.getBlock() instanceof ChestBlock
                && stateAtPosition.hasProperty(ChestBlock.TYPE)
                && stateAtPosition.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            claimed.add(immutable.relative(ChestBlock.getConnectedDirection(stateAtPosition)));
        }

        pendingBySection
                .computeIfAbsent(sectionKey(immutable.getX() >> 4, immutable.getY() >> 4,
                        immutable.getZ() >> 4), k -> new ArrayList<>())
                .add(immutable);
        pendingCount++;
    }

    // ---- 就近取目标 ----

    /**
     * 取出玩家周围区段里最近且够得着的待缓存容器，并从索引中移除。
     *
     * <p>只遍历玩家所在区段及其邻居，所以选区里有多少容器都不影响这一步的开销。
     */
    private static BlockPos takeNearestReachable(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        double reach = PlayerUtil.blockReach(client.player);
        double reachSq = reach * reach;
        int radius = (int) Math.ceil((reach + 1.0) / 16.0);

        BlockPos playerPos = client.player.blockPosition();
        int centerX = playerPos.getX() >> 4;
        int centerY = playerPos.getY() >> 4;
        int centerZ = playerPos.getZ() >> 4;
        Set<Block> whitelist = AutoCacheNearbyContainersFeature.whitelistedBlocks();

        while (!pendingBySection.isEmpty()) {
            List<BlockPos> bestBucket = null;
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        List<BlockPos> bucket = pendingBySection.get(
                                sectionKey(centerX + dx, centerY + dy, centerZ + dz));
                        if (bucket == null) continue;

                        for (BlockPos pos : bucket) {
                            double distSq = distanceToBlockSq(eye, pos);
                            if (distSq <= reachSq && distSq < bestDistSq) {
                                bestDistSq = distSq;
                                best = pos;
                                bestBucket = bucket;
                            }
                        }
                    }
                }
            }

            if (best == null) return null;

            bestBucket.remove(best);
            if (bestBucket.isEmpty()) {
                pendingBySection.remove(
                        sectionKey(best.getX() >> 4, best.getY() >> 4, best.getZ() >> 4));
            }
            pendingCount--;

            // 世界变了（容器被拆掉）就当没这个目标，接着找下一个。
            if (whitelist.contains(client.level.getBlockState(best).getBlock())) {
                return best;
            }
        }
        return null;
    }

    /**
     * 眼睛到方块外框的最短距离平方。
     *
     * <p>用外框而不是方块中心，判定与服务端接受右键的范围一致；用中心会白白少半格多。
     */
    private static double distanceToBlockSq(Vec3 eye, BlockPos pos) {
        double dx = Math.max(Math.abs(eye.x - (pos.getX() + 0.5)) - 0.5, 0.0);
        double dy = Math.max(Math.abs(eye.y - (pos.getY() + 0.5)) - 0.5, 0.0);
        double dz = Math.max(Math.abs(eye.z - (pos.getZ() + 0.5)) - 0.5, 0.0);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 打包区段坐标：x/z 各 22 位、y 20 位，与原版 {@code SectionPos.asLong} 同一套划分。
     * 22 位足够覆盖世界边界（±3000 万格 ÷ 16 ≈ ±188 万区段）而不会撞键。
     */
    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    // ---- 容器交互 ----

    /** 朝最靠近玩家的那个面发右键，隔空开箱，避免打到相邻方块。 */
    private static void sendOpenClick(Minecraft client, BlockPos position) {
        if (client.gameMode == null) return;

        Vec3 eye = client.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(position);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        Direction face;
        if (ax >= ay && ax >= az) {
            face = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= az) {
            face = dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        Vec3 hitPos = new Vec3(
                center.x + face.getStepX() * 0.5,
                center.y + face.getStepY() * 0.5,
                center.z + face.getStepZ() * 0.5);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitPos, face, position, false));
    }

    private static boolean menuHasContent(Minecraft client) {
        for (Slot slot : client.player.containerMenu.slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean saveContainerToChestTracker(Minecraft client) {
        if (currentTarget == null) return false;
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) return false;

        return ChestTrackerIntegration.getInstance().cacheContainer(
                client.level, currentTarget, client.level.getBlockState(currentTarget), menu);
    }

    private static void closeGuiIfOpen(Minecraft client) {
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        ChestTrackerIntegration.getInstance().clearInteractionTracker();
    }

    /** 限流的 ActionBar 提示：一秒最多一条，避免每秒开二十个箱子时刷屏。 */
    private static void notify(Minecraft client, String translationKey, Object... args) {
        if (messageTimer > 0) return;
        messageTimer = MESSAGE_INTERVAL;
        MessageUtil.sendActionBar(client, translationKey, args);
    }
}
