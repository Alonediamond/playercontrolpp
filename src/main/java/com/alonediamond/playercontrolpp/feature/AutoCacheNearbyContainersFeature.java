package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 把手长范围内每个白名单容器打开一次让箱子追踪记录内容，然后关掉。
 * 每次任务维护一份已访问集合，同一个容器不会被反复打开。
 *
 * <pre>SCANNING → OPENING_CONTAINER → [COOLDOWN] → SCANNING</pre>
 *
 * <p>附近没有未缓存容器时进入 AUTO_STOP_COUNTDOWN，三秒内以较低频率继续找，
 * 所以走到下一个房间会自动接着干。
 *
 * <h3>开箱全程无 GUI，落库走直写</h3>
 * 功能运行期间 {@link ContainerGuiSuppressor} 会吞掉 {@code setScreen}——界面从未创建，
 * 玩家看不到任何开关箱闪烁。代价是 ChestTracker 挂在 {@code ScreenEvents.remove} 上的
 * 「关界面落库」钩子失去了依附的 Screen，所以这里改成内容包一到位就用
 * {@code MemoryBuilder} 直写缓存库（与投影选区缓存同一条路），然后照常关掉菜单。
 * 判据也从「界面出现了」换成「菜单已打开」——两者的赋值都发生在服务端回包的同一时刻，
 * 只是后者不依赖 Screen 存在。
 */

public class AutoCacheNearbyContainersFeature {

    private enum State {
        SCANNING,               // 找范围内未缓存的容器
        OPENING_CONTAINER,      // 右键已发出，等界面与内容包
        COOLDOWN,               // 配置的容器间隔
        AUTO_STOP_COUNTDOWN     // 没得干了，倒计时关闭
    }

    /** 自动停止的宽限期：20 tps 下 3 秒。 */
    private static final int AUTO_STOP_TICKS = 60;
    /** 倒计时期间的重扫间隔——扫描是这里最贵的一步，而玩家四分之一秒走不了多远。 */
    private static final int COUNTDOWN_SCAN_INTERVAL = 5;
    /** 点击后等容器界面的 tick 数。 */
    private static final int OPEN_WAIT_TICKS = 10;
    /** 界面已开但内容包还没到时，最多再宽限的 tick 数。 */
    private static final int CONTENT_WAIT_TICKS = 2;
    /** 单 tick 内最多推进几步状态；纯粹是防御性上限，正常只会走 1~2 步。 */
    private static final int MAX_STEPS_PER_TICK = 8;

    private static boolean enabled;
    private static final Set<BlockPos> visitedContainers = new HashSet<>();
    private static State state = State.SCANNING;
    private static BlockPos currentTarget;
    private static int stateTimer;
    private static int contentTimer;
    private static int autoStopCountdown;

    /**
     * 白名单从 id 解析成方块实例后的结果。
     *
     * <p>早先的扫描是把方块的注册 id 拼成字符串再查表——立方体里约 1300 个位置，每 tick
     * 每个位置一次注册表反查加一次字符串分配。解析成 {@code Set<Block>} 后内层只剩一次引用哈希查表。
     */
    private static Set<Block> whitelistBlocks = Collections.emptySet();
    /** 构建 {@link #whitelistBlocks} 时用的配置值，用来发现玩家改了配置。 */
    private static List<String> whitelistSource;

    /** 注册进 {@link FeatureRegistry}，见 {@code InitHandler}。 */
    public static final ClientFeature FEATURE = new ClientFeature() {
        @Override public void onClientTick(Minecraft mc) { tick(mc); }
        @Override public void onWorldChange() { AutoCacheNearbyContainersFeature.onWorldChange(); }
        @Override public boolean isActive() { return enabled; }
    };

    private AutoCacheNearbyContainersFeature() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        if (!enabled && SchematicSelectionContainerCacheFeature.isActive()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.schematic_cache.busy");
            return;
        }
        enabled = !enabled;
        if (enabled) {
            resetState();
            MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.on");
        } else {
            closeGuiIfOpen(client);
            resetState();
            MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.off");
        }
    }

    public static void onWorldChange() {
        if (enabled) {
            enabled = false;
            resetState();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.world_change");
            }
        }
    }

    private static void resetState() {
        visitedContainers.clear();
        currentTarget = null;
        state = State.SCANNING;
        stateTimer = 0;
        contentTimer = 0;
        autoStopCountdown = 0;
        // 状态清零时同步撤掉 GUI 屏蔽；开着的情况下下一 tick 会立刻续租。
        ContainerGuiSuppressor.expire();
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // 功能活跃期间维持 GUI 屏蔽租约（每 tick 续，停止后一个租约期内自愈）。
        ContainerGuiSuppressor.renew();

        // 尊重其它界面：玩家开着非容器界面时整体暂停，不去抢他的操作。
        if (ScreenCompat.getScreen(mc) != null
                && !(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen)) {
            return;
        }

        // 没有等待外部事件的状态在同一 tick 内连续推进：关箱与开下一个箱之间不再各自浪费一个 tick。
        for (int step = 0; step < MAX_STEPS_PER_TICK && advance(mc); step++) {
            // advance() 返回 true 表示这一步没有消耗服务端往返，可以立刻继续。
        }
    }

    /**
     * 推进一步状态机。
     *
     * @return {@code true} 表示这一步没有等待服务端，可以在同一 tick 内继续推进。
     */
    private static boolean advance(Minecraft mc) {
        switch (state) {
            case SCANNING -> {
                return tickScanning(mc);
            }
            case OPENING_CONTAINER -> {
                return tickOpeningContainer(mc);
            }
            case COOLDOWN -> {
                if (--stateTimer <= 0) {
                    state = State.SCANNING;
                    return true;
                }
                return false;
            }
            case AUTO_STOP_COUNTDOWN -> {
                return tickAutoStopCountdown(mc);
            }
        }
        return false;
    }

    private static boolean tickScanning(Minecraft mc) {
        // 还有容器菜单开着就先关掉：可能是上一个容器超时之后才姗姗来迟地打开。
        // 它没被访问标记过，直接丢弃不落库，下一轮会重新正常开一次。
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeGuiIfOpen(mc);
            return false;
        }

        BlockPos target = findNearestUncachedContainer(mc);
        if (target == null) {
            state = State.AUTO_STOP_COUNTDOWN;
            autoStopCountdown = AUTO_STOP_TICKS;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.all_cached");
            return false;
        }

        beginOpen(mc, target);
        return false; // 等服务端回包
    }

    private static void beginOpen(Minecraft mc, BlockPos target) {
        currentTarget = target;
        stateTimer = OPEN_WAIT_TICKS;
        contentTimer = CONTENT_WAIT_TICKS;
        state = State.OPENING_CONTAINER;
        openContainer(mc, target);
    }

    private static boolean tickOpeningContainer(Minecraft mc) {
        // 界面被屏蔽时 Screen 恒为 null，判据换成「菜单已打开」——菜单赋值与 setScreen
        // 都发生在服务端回包的同一时刻（MenuScreens.fromPacket），只是前者不依赖 Screen。
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            markVisited();

            // 菜单已开，但物品内容是另一个包。等到看见东西、或宽限用完再落库，
            // 否则会把还没同步的容器记成空的。
            if (!menuHasContent(mc) && --contentTimer > 0) {
                return false;
            }

            // 界面被吞了，ChestTracker 的关界面钩子没有 Screen 可读——内容一到就直写缓存库。
            saveContainerToChestTracker(mc);
            closeGuiIfOpen(mc);
            currentTarget = null;

            int delay = Configs.Settings.CACHE_DELAY.getIntegerValue();
            if (delay > 0) {
                state = State.COOLDOWN;
                stateTimer = delay;
                return false;
            }
            state = State.SCANNING;
            return true; // 延迟为 0 时同一 tick 直接扫下一个
        }

        if (--stateTimer <= 0) {
            // 一直没开成——也标记掉，免得永远重试同一个。
            markVisited();
            currentTarget = null;
            state = State.SCANNING;
            return true; // 没消耗往返，立刻找下一个
        }
        return false;
    }

    /** 把当前目标容器直写进箱子追踪的缓存库（与投影选区缓存同一条路）。 */
    private static boolean saveContainerToChestTracker(Minecraft mc) {
        if (currentTarget == null) return false;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) return false;

        return ChestTrackerIntegration.getInstance().cacheContainer(
                mc.level, currentTarget, mc.level.getBlockState(currentTarget), menu);
    }

    private static boolean tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        if (autoStopCountdown % COUNTDOWN_SCAN_INTERVAL == 0) {
            BlockPos found = findNearestUncachedContainer(mc);
            if (found != null) {
                // 直接用这次扫到的目标，不要回 SCANNING 再扫一遍。
                beginOpen(mc, found);
                return false;
            }
        }

        if (autoStopCountdown <= 0) {
            enabled = false;
            resetState();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.auto_stop");
        }
        return false;
    }

    private static void markVisited() {
        if (currentTarget != null) {
            visitedContainers.add(currentTarget);
        }
    }

    /** @return 当前打开的容器菜单里，属于容器那一侧的槽位是否已经有物品了。 */
    private static boolean menuHasContent(Minecraft mc) {
        for (Slot slot : mc.player.containerMenu.slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单趟扫过受手长限制的立方体，只留最近的那个。
     *
     * <p>复用一个 {@link BlockPos.MutableBlockPos}，不是每个位置分配一个；直接返回最近的那个，
     * 不收集全部再排序——排完序也只会读第一个。
     *
     * @return 最近的、未访问过的白名单容器；没有则 {@code null}
     */
    private static BlockPos findNearestUncachedContainer(Minecraft mc) {
        Set<Block> whitelist = whitelistBlocks();
        if (whitelist.isEmpty()) return null;

        Level level = mc.level;
        double range = PlayerUtil.blockReach(mc.player);
        int rangeInt = (int) Math.ceil(range);
        double rangeSq = range * range;

        BlockPos playerPos = mc.player.blockPosition();
        int px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -rangeInt; dy <= rangeInt; dy++) {
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > rangeSq || distSq >= bestDistSq) continue;

                    cursor.set(px + dx, py + dy, pz + dz);
                    if (visitedContainers.contains(cursor)) continue;
                    if (!whitelist.contains(level.getBlockState(cursor).getBlock())) continue;

                    best = cursor.immutable(); // 必须复制：游标还要继续动
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    /**
     * @return 解析成方块实例的白名单，只在配置列表变化时重建。
     *
     * <p>做法是把方块注册表走一遍、留下 id 在列表里的项。这样可以绕开 {@code Registry.get(id)}
     * 返回类型的版本差异——它在部分支持版本上是 {@code Optional<Block>}，在另一些上是 Holder。
     */
    private static Set<Block> whitelistBlocks() {
        List<String> configured = Configs.CacheNearbySettings.CONTAINER_WHITELIST.getStrings();
        if (configured.equals(whitelistSource)) {
            return whitelistBlocks;
        }

        whitelistSource = List.copyOf(configured);
        Set<String> ids = new HashSet<>(whitelistSource);
        Set<Block> resolved = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block block : BuiltInRegistries.BLOCK) {
            if (ids.contains(BuiltInRegistries.BLOCK.getKey(block).toString())) {
                resolved.add(block);
            }
        }
        whitelistBlocks = resolved;
        return whitelistBlocks;
    }

    /** 供「缓存投影选区容器」共用同一份白名单，避免它自己再解析一遍配置。 */
    static Set<Block> whitelistedBlocks() {
        return whitelistBlocks();
    }

    private static void openContainer(Minecraft mc, BlockPos target) {
        if (mc.player == null || mc.gameMode == null) return;

        Direction face = getNearestFace(mc.player.getEyePosition(), target);
        Vec3 hitPos = new Vec3(
                target.getX() + 0.5 + face.getStepX() * 0.5,
                target.getY() + 0.5 + face.getStepY() * 0.5,
                target.getZ() + 0.5 + face.getStepZ() * 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(hitPos, face, target, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
    }

    private static Direction getNearestFace(Vec3 playerEye, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        double dx = playerEye.x - center.x;
        double dy = playerEye.y - center.y;
        double dz = playerEye.z - center.z;

        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void closeGuiIfOpen(Minecraft mc) {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }
}
