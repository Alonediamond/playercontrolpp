package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
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
 * Opens every whitelisted container in reach so ChestTracker can record its contents, then
 * closes it again. A per-session visited set stops it from re-opening the same one.
 *
 * <pre>
 * SCANNING -&gt; OPENING_CONTAINER -&gt; WAITING_AFTER_OPEN -&gt; CLOSING_GUI -&gt; [COOLDOWN] -&gt; SCANNING
 * </pre>
 *
 * <p>With nothing left uncached it enters AUTO_STOP_COUNTDOWN, which keeps looking at a reduced
 * rate for three seconds so walking to the next room resumes it automatically.
 */
public class AutoCacheNearbyContainersFeature {

    private enum State {
        SCANNING,               // Looking for uncached containers in range
        OPENING_CONTAINER,      // Click sent, waiting for the screen
        WAITING_AFTER_OPEN,     // Screen up, give ChestTracker a tick to record
        CLOSING_GUI,            // Closed, brief settle before the next scan
        COOLDOWN,               // Configured delay between containers
        AUTO_STOP_COUNTDOWN     // Nothing left; counting down to switch off
    }

    /** Auto-stop grace period: 3 seconds at 20 tps. */
    private static final int AUTO_STOP_TICKS = 60;
    /** During the countdown, only re-scan this often — the sweep is the expensive part. */
    private static final int COUNTDOWN_SCAN_INTERVAL = 5;
    /** Ticks to wait for the container screen after clicking. */
    private static final int OPEN_WAIT_TICKS = 10;
    /** Ticks to leave the screen open so ChestTracker sees the contents. */
    private static final int RECORD_WAIT_TICKS = 1;
    /** Ticks to settle after closing before scanning again. */
    private static final int CLOSE_SETTLE_TICKS = 2;

    private static boolean enabled;
    private static final Set<BlockPos> visitedContainers = new HashSet<>();
    private static State state = State.SCANNING;
    private static BlockPos currentTarget;
    private static int stateTimer;
    private static int autoStopCountdown;

    /**
     * The whitelist resolved from ids to block instances.
     *
     * <p>The scan used to build the block's registry id as a String and look that up — a registry
     * reverse-lookup plus a String allocation for each of the ~1300 positions in the cube, every
     * tick. Resolving once to {@code Set<Block>} makes the inner check an identity-hash lookup.
     */
    private static Set<Block> whitelistBlocks = Collections.emptySet();
    /** The config value {@link #whitelistBlocks} was built from, to detect edits. */
    private static List<String> whitelistSource;

    /** Registered with {@link FeatureRegistry}; see {@code InitHandler}. */
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
        autoStopCountdown = 0;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // Respect other GUIs: scanning and waiting need no screen, but the interactive states
        // must not run while the player has something else open.
        if (ScreenCompat.getScreen(mc) != null
                && state != State.SCANNING
                && state != State.COOLDOWN
                && state != State.AUTO_STOP_COUNTDOWN
                && !(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen)) {
            return;
        }

        switch (state) {
            case SCANNING -> tickScanning(mc);
            case OPENING_CONTAINER -> tickOpeningContainer(mc);
            case WAITING_AFTER_OPEN -> tickWaitingAfterOpen(mc);
            case CLOSING_GUI -> tickClosingGui(mc);
            case COOLDOWN -> tickCooldown(mc);
            case AUTO_STOP_COUNTDOWN -> tickAutoStopCountdown(mc);
        }
    }

    private static void tickScanning(Minecraft mc) {
        BlockPos target = findNearestUncachedContainer(mc);
        if (target == null) {
            state = State.AUTO_STOP_COUNTDOWN;
            autoStopCountdown = AUTO_STOP_TICKS;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.all_cached");
        } else {
            currentTarget = target;
            openContainer(mc, target);
            state = State.OPENING_CONTAINER;
            stateTimer = OPEN_WAIT_TICKS;
        }
    }

    private static void tickOpeningContainer(Minecraft mc) {
        stateTimer--;
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            markVisited();
            state = State.WAITING_AFTER_OPEN;
            stateTimer = RECORD_WAIT_TICKS;
        } else if (stateTimer <= 0) {
            // Never opened — mark it anyway so we do not retry it forever.
            markVisited();
            currentTarget = null;
            state = State.SCANNING;
        }
    }

    private static void markVisited() {
        if (currentTarget != null) {
            visitedContainers.add(currentTarget);
        }
    }

    private static void tickWaitingAfterOpen(Minecraft mc) {
        if (--stateTimer <= 0) {
            closeGuiIfOpen(mc);
            state = State.CLOSING_GUI;
            stateTimer = CLOSE_SETTLE_TICKS;
        }
    }

    private static void tickClosingGui(Minecraft mc) {
        if (--stateTimer <= 0) {
            currentTarget = null;
            int delay = Configs.Settings.CACHE_DELAY.getIntegerValue();
            if (delay > 0) {
                state = State.COOLDOWN;
                stateTimer = delay;
            } else {
                state = State.SCANNING;
            }
        }
    }

    private static void tickCooldown(Minecraft mc) {
        if (--stateTimer <= 0) {
            state = State.SCANNING;
        }
    }

    private static void tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        if (autoStopCountdown % COUNTDOWN_SCAN_INTERVAL == 0
                && findNearestUncachedContainer(mc) != null) {
            state = State.SCANNING;
            return;
        }

        if (autoStopCountdown <= 0) {
            enabled = false;
            resetState();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.auto_stop");
        }
    }

    /**
     * Single pass over the reach-limited cube, keeping only the closest hit.
     *
     * <p>Reuses one {@link BlockPos.MutableBlockPos} instead of allocating per position, and
     * returns the nearest directly rather than collecting every hit and sorting a list whose
     * first element was the only one ever read.
     *
     * @return the closest whitelisted container not yet visited, or {@code null}
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

                    best = cursor.immutable(); // must copy: the cursor keeps moving
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    /**
     * @return the whitelist as block instances, rebuilt only when the config string list changes.
     *
     * <p>Resolves by walking the block registry once and keeping the entries whose id is listed.
     * That avoids per-version differences in the {@code Registry.get(id)} return type, which is
     * plain {@code Optional<Block>} on some of the supported versions and a Holder on others.
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
        if (mc.player != null && ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }
    }
}
