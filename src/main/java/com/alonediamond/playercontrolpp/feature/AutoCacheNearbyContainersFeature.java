package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-caches nearby container contents by simulating right-click to open each
 * container in range, waiting for the ChestTracker mod to record items, then
 * auto-closing. Uses a 5-state tick-driven state machine and a per-session
 * visited set to avoid re-opening the same container.
 *
 * State flow: SCANNING → OPENING_CONTAINER → WAITING_AFTER_OPEN → CLOSING_GUI → SCANNING
 * When no uncached containers remain in range → AUTO_STOP_COUNTDOWN (3-second timer).
 */
public class AutoCacheNearbyContainersFeature {

    /** 6-state tick-driven state machine for container caching. */
    private enum State {
        SCANNING,               // Looking for uncached containers in range
        OPENING_CONTAINER,      // Sent interactBlock, waiting for GUI (up to 10 ticks)
        WAITING_AFTER_OPEN,     // GUI opened, wait 1 tick for ChestTracker to record
        CLOSING_GUI,            // Closed GUI, brief cooldown before next scan
        COOLDOWN,               // Waiting configured delay ticks between containers
        AUTO_STOP_COUNTDOWN     // No uncached containers, counting down to auto-stop
    }

    private static boolean enabled;
    private static final Set<BlockPos> visitedContainers = new HashSet<>();
    private static State state = State.SCANNING;
    private static BlockPos currentTarget;
    private static int stateTimer;
    private static int autoStopCountdown;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        enabled = !enabled;
        if (enabled) {
            visitedContainers.clear();
            currentTarget = null;
            state = State.SCANNING;
            stateTimer = 0;
            autoStopCountdown = 0;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.on");
        } else {
            closeGuiIfOpen(client);
            visitedContainers.clear();
            currentTarget = null;
            state = State.SCANNING;
            MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.off");
        }
    }

    public static void onWorldChange() {
        if (enabled) {
            enabled = false;
            visitedContainers.clear();
            currentTarget = null;
            state = State.SCANNING;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.world_change");
            }
        }
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        // Respect other GUIs — only proceed if we own the current screen interaction
        if (ScreenCompat.getScreen(mc) != null) {
            if (state == State.SCANNING || state == State.AUTO_STOP_COUNTDOWN || state == State.COOLDOWN) {
                // Allow scanning/cooldown/countdown to continue (no screen interaction needed)
            } else if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen)) {
                return;
            }
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
        double range = getInteractionRange(mc);
        BlockPos playerPos = mc.player.blockPosition();
        List<BlockPos> nearbyContainers = scanContainers(mc, playerPos, range);

        if (nearbyContainers.isEmpty()) {
            // No uncached containers nearby
            if (state == State.SCANNING) {
                state = State.AUTO_STOP_COUNTDOWN;
                autoStopCountdown = 60; // 3 seconds at 20 tps
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.all_cached");
            }
        } else {
            // Cancel auto-stop if we were counting down
            currentTarget = nearbyContainers.get(0);
            openContainer(mc, currentTarget);
            state = State.OPENING_CONTAINER;
            stateTimer = 10; // Wait up to 10 ticks for GUI to open
        }
    }

    private static void tickOpeningContainer(Minecraft mc) {
        stateTimer--;
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            // GUI opened successfully
            if (currentTarget != null) {
                visitedContainers.add(currentTarget);
            }
            state = State.WAITING_AFTER_OPEN;
            stateTimer = 1; // Wait 1 tick with GUI open
        } else if (stateTimer <= 0) {
            // Failed to open within timeout, mark as visited and move on
            if (currentTarget != null) {
                visitedContainers.add(currentTarget);
            }
            currentTarget = null;
            state = State.SCANNING;
        }
    }

    private static void tickWaitingAfterOpen(Minecraft mc) {
        stateTimer--;
        if (stateTimer <= 0) {
            closeGuiIfOpen(mc);
            state = State.CLOSING_GUI;
            stateTimer = 2; // Brief cooldown after closing
        }
    }

    private static void tickClosingGui(Minecraft mc) {
        stateTimer--;
        if (stateTimer <= 0) {
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
        stateTimer--;
        if (stateTimer <= 0) {
            state = State.SCANNING;
        }
    }

    private static void tickAutoStopCountdown(Minecraft mc) {
        autoStopCountdown--;

        // Re-scan to check if new containers appeared
        double range = getInteractionRange(mc);
        BlockPos playerPos = mc.player.blockPosition();
        List<BlockPos> nearbyContainers = scanContainers(mc, playerPos, range);

        if (!nearbyContainers.isEmpty()) {
            // New container found, cancel auto-stop
            state = State.SCANNING;
            return;
        }

        if (autoStopCountdown <= 0) {
            enabled = false;
            visitedContainers.clear();
            currentTarget = null;
            state = State.SCANNING;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.cache_nearby.auto_stop");
        }
    }

    private static List<BlockPos> scanContainers(Minecraft mc, BlockPos playerPos, double range) {
        List<BlockPos> result = new ArrayList<>();
        int rangeInt = (int) Math.ceil(range);
        Set<String> whitelist = new HashSet<>(Configs.CacheNearbySettings.CONTAINER_WHITELIST.getStrings());
        Level level = mc.level;

        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -rangeInt; dy <= rangeInt; dy++) {
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > range * range) continue;

                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (visitedContainers.contains(pos)) continue;

                    String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
                    if (whitelist.contains(blockId)) {
                        result.add(pos);
                    }
                }
            }
        }

        // Sort by distance from player (nearest first)
        result.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));
        return result;
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

    private static double getInteractionRange(Minecraft mc) {
        if (mc.player == null) return 4.5;
        return mc.player.isCreative() ? 5.0 : 4.5;
    }

    private static void closeGuiIfOpen(Minecraft mc) {
        if (mc.player != null && ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }
    }
}
