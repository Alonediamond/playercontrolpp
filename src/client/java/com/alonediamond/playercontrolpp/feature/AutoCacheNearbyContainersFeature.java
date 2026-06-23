package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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

    public static void toggle(MinecraftClient client) {
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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.cache_nearby.world_change");
            }
        }
    }

    public static void tick(MinecraftClient mc) {
        if (!enabled || mc.player == null || mc.world == null) return;

        // Respect other GUIs — only proceed if we own the current screen interaction
        if (mc.currentScreen != null) {
            if (state == State.SCANNING || state == State.AUTO_STOP_COUNTDOWN || state == State.COOLDOWN) {
                // Allow scanning/cooldown/countdown to continue (no screen interaction needed)
            } else if (!(mc.currentScreen instanceof HandledScreen)) {
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

    private static void tickScanning(MinecraftClient mc) {
        double range = getInteractionRange(mc);
        BlockPos playerPos = mc.player.getBlockPos();
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

    private static void tickOpeningContainer(MinecraftClient mc) {
        stateTimer--;
        if (mc.currentScreen instanceof HandledScreen) {
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

    private static void tickWaitingAfterOpen(MinecraftClient mc) {
        stateTimer--;
        if (stateTimer <= 0) {
            closeGuiIfOpen(mc);
            state = State.CLOSING_GUI;
            stateTimer = 2; // Brief cooldown after closing
        }
    }

    private static void tickClosingGui(MinecraftClient mc) {
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

    private static void tickCooldown(MinecraftClient mc) {
        stateTimer--;
        if (stateTimer <= 0) {
            state = State.SCANNING;
        }
    }

    private static void tickAutoStopCountdown(MinecraftClient mc) {
        autoStopCountdown--;

        // Re-scan to check if new containers appeared
        double range = getInteractionRange(mc);
        BlockPos playerPos = mc.player.getBlockPos();
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

    private static List<BlockPos> scanContainers(MinecraftClient mc, BlockPos playerPos, double range) {
        List<BlockPos> result = new ArrayList<>();
        int rangeInt = (int) Math.ceil(range);
        Set<String> whitelist = new HashSet<>(Configs.CacheNearbySettings.CONTAINER_WHITELIST.getStrings());
        World world = mc.world;

        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -rangeInt; dy <= rangeInt; dy++) {
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > range * range) continue;

                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (visitedContainers.contains(pos)) continue;

                    String blockId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
                    if (whitelist.contains(blockId)) {
                        result.add(pos);
                    }
                }
            }
        }

        // Sort by distance from player (nearest first)
        result.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(playerPos)));
        return result;
    }

    private static void openContainer(MinecraftClient mc, BlockPos target) {
        if (mc.player == null || mc.interactionManager == null) return;

        Direction face = getNearestFace(mc.player.getEyePos(), target);
        Vec3d hitPos = new Vec3d(
                target.getX() + 0.5 + face.getOffsetX() * 0.5,
                target.getY() + 0.5 + face.getOffsetY() * 0.5,
                target.getZ() + 0.5 + face.getOffsetZ() * 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(hitPos, face, target, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
    }

    private static Direction getNearestFace(Vec3d playerEye, BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
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

    private static double getInteractionRange(MinecraftClient mc) {
        if (mc.player == null) return 4.5;
        return mc.player.isCreative() ? 5.0 : 4.5;
    }

    private static void closeGuiIfOpen(MinecraftClient mc) {
        if (mc.player != null && mc.currentScreen instanceof HandledScreen) {
            mc.player.closeHandledScreen();
        }
    }
}
