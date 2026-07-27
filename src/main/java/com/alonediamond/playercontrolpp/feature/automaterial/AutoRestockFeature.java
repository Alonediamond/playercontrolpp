package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Monitors Baritone's {@code #litematica} builder for material-shortage pauses, then navigates to
 * player-marked container positions to restock and resumes the build.
 *
 * <h3>Activation</h3>
 * <ul>
 *   <li><b>One-click Build + Restock</b> (hotkey) — toggle. Press once to start the build AND
 *       enable monitoring; press again to stop. The feature watches for builder pauses, runs a
 *       restock trip across marked containers, then restarts the builder via
 *       {@code buildOpenLitematic(0)} and loops.</li>
 *   <li><b>Mark Container</b> (hotkey) — marks/unmarks the container the player is looking at.
 *       Entries are stored in {@code Configs.Restocks.MARKED_CONTAINERS} and editable from the
 *       config GUI.</li>
 * </ul>
 *
 * <h3>Flow</h3>
 * <pre>
 * IDLE → MONITORING → ANALYZING → PATHING → OPENING → TRANSFERRING → RESTARTING → MONITORING
 * </pre>
 */
public class AutoRestockFeature implements ClientFeature {

    public static void onMarkContainer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == BlockHitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            MarkedContainerManager mgr = MarkedContainerManager.getInstance();
            if (mgr.contains(pos, mc.level)) {
                mgr.remove(pos, mc.level);
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_unmarked",
                        pos.getX(), pos.getY(), pos.getZ());
            } else {
                mgr.add(pos, mc.level);
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_marked",
                        pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }

    public static void onOneClickBuild() {
        getInstance().startOneClick();
    }

    // ---- Singleton & feature interface ----

    private static final AutoRestockFeature INSTANCE = new AutoRestockFeature();

    private AutoRestockFeature() {}

    public static AutoRestockFeature getInstance() { return INSTANCE; }

    enum State {
        IDLE, MONITORING, ANALYZING, PATHING, OPENING, TRANSFERRING,SHULKERING, RESTARTING
    }

    private State state = State.IDLE;
    private boolean active;
    /** True while the material HUD check is pending (1 tick delay to let the GUI settle). */
    private boolean hudCheckPending;
    /** Guards against calling buildOpenLitematic every tick when the builder is not active. */
    private boolean attemptedBuildLaunch;
    /** Count of consecutive restart cycles with zero items transferred. Guards against infinite restart loops. */
    private int dryRestartCount;
    /** Max restarts without any items transferred before stopping. */
    private static final int MAX_DRY_RESTARTS = 3;

    private final BaritoneIntegration baritone = BaritoneIntegration.getInstance();
    private final LitematicaIntegration litematica = LitematicaIntegration.getInstance();
    private final MarkedContainerManager containerManager = MarkedContainerManager.getInstance();

    // Restock-run transient state
    private final List<MaterialItemEntry> neededItems = new ArrayList<>();
    private final List<BlockPos> containerQueue = new ArrayList<>();
    private int containerIndex;
    private BlockPos currentContainerTarget;
    private int pathingTicks;
    private int stuckTicks;
    private int openCooldown;
    private int openRetries;
    private int transferCooldown;
    /** How many ticks since we last transferred anything from the current container screen. */
    private int dryTicks;
    private boolean anyTransferredThisRun;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    private int haveShulkerBoxesContainsItems = 0;

    private static final int OPEN_WAIT_TICKS = 12;
    private static final int MAX_OPEN_RETRIES = 3;
    /** Consecutive transfer ticks with no item moved before we treat the container as empty. */
    private static final int MAX_DRY_TICKS = 8;
    private static final int PATHING_STUCK_LIMIT = 120;
    private static final int PATHING_SETTLE = 5;
    private static final double STUCK_EPSILON_SQ = 0.04;

    // ---- Public API ----

    @Override
    public boolean isActive() { return active; }

    @Override
    public void onClientTick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.player.isDeadOrDying()) {
            if (active) {
                stop("playercontrolpp.message.restock.player_died");
            }
            return;
        }

        // HUD check is deferred by one tick so the closeContainer / buildOpenLitematic call has
        // time to resolve before we read the material list.
        if (hudCheckPending) {
            hudCheckPending = false;
            doAnalyze(mc);
            return;
        }

        if (transferCooldown > 0) { transferCooldown--; }

        switch (state) {
            case IDLE -> {} // unreachable while active
            case MONITORING -> tickMonitoring(mc);
            case ANALYZING -> tickAnalyzing(mc);
            case PATHING -> tickPathing(mc);
            case OPENING -> tickOpening(mc);
            case TRANSFERRING -> tickTransferring(mc);
            case SHULKERING -> tickGetItemFromShulker(mc);
            case RESTARTING -> tickRestarting(mc);
        }
    }

    private void tickGetItemFromShulker(Minecraft mc) {

    }

    @Override
    public void onWorldChange() {
        if (active) {
            stop("playercontrolpp.message.restock.world_change");
        }
    }

    // ---- Activation ----

    /** Toggle: start if idle, stop if running. The one-click hotkey calls this. */
    private void startOneClick() {
        if (active) {
            stop("playercontrolpp.message.restock.stopped");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!litematica.isLoaded() || !baritone.isLoaded()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.mods_missing");
            return;
        }

        active = true;
        state = State.MONITORING;
        attemptedBuildLaunch = false;
        dryRestartCount = 0;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.started");
    }

    private void stop(String messageKey) {
        baritone.cancelPathing();
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.closeContainer();
        }
        active = false;
        state = State.IDLE;
        hudCheckPending = false;
        neededItems.clear();
        containerQueue.clear();
        if (messageKey != null) {
            MessageUtil.sendActionBar(Minecraft.getInstance(), messageKey);
        }
    }

    // ---- Tick handlers ----

    private void tickMonitoring(Minecraft mc) {
        // Builder not active: either it hasn't been launched yet, or it completed.
        // Only launch once — without this guard the completed builder is re-launched
        // every tick, producing an infinite build-restart loop.
        if (!baritone.isBuilderActive()) {
            if (!attemptedBuildLaunch) {
                attemptedBuildLaunch = true;
                if (baritone.startLitematicaBuild(0)) {
                    MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_started");
                } else {
                    MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
                    stop(null);
                }
            }
            return;
        }

        // Builder IS active — confirmed; reset the guard so a future restock cycle
        // can re-launch it.
        attemptedBuildLaunch = true;

        if (baritone.isBuilderPaused()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.pause_detected");
            state = State.ANALYZING;
        }
    }

    private void tickAnalyzing(Minecraft mc) {
        // Defer the actual analysis by one tick so that any closeContainer / buildOpenLitematic
        // call has had a tick to resolve, and the material-list HUD has had a frame to refresh.
        hudCheckPending = true;
    }

    /** Reads Litematica's material list, checks HUD state, and decides next action. */
    private void doAnalyze(Minecraft mc) {
        neededItems.clear();
        containerQueue.clear();
        anyTransferredThisRun = false;

        // Read Litematica material list.
        try {
            Object materialList = litematica.getMaterialList();
            if (materialList == null) {
                cannotReadMaterials(mc);
                return;
            }

            // Litematica only keeps the list up to date while its HUD is on.
            Object hudRenderer = materialList.getClass()
                    .getMethod("getHudRenderer").invoke(materialList);
            boolean hudShowing = (Boolean) hudRenderer.getClass()
                    .getMethod("getShouldRenderCustom").invoke(hudRenderer);
            if (!hudShowing) {
                cannotReadMaterials(mc);
                return;
            }

            // Ensure counts are up to date.
            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            Class.forName("fi.dy.masa.litematica.materials.MaterialListUtils")
                    .getMethod("updateAvailableCounts", List.class,
                            net.minecraft.world.entity.player.Player.class)
                    .invoke(null, allMaterials, mc.player);

            @SuppressWarnings("unchecked")
            List<?> allList = (List<?>) allMaterials;
            if (allList.isEmpty()) {
                cannotReadMaterials(mc);
                return;
            }

            for (Object entry : allList) {
                ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                int countMissing = (Integer) entry.getClass().getMethod("getCountMissing").invoke(entry);
                if (countMissing > 0) {
                    neededItems.add(new MaterialItemEntry(stack.getItem(), countMissing));
                }
            }

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Auto-restock: failed to read Litematica material list", e);
            cannotReadMaterials(mc);
            return;
        }

        if (neededItems.isEmpty()) {
            // Builder paused for a non-material reason (pathing failure, liquid, …).
            restartBuilder(mc);
            return;
        }

        // Build the queue of marked containers in the current dimension.
        List<BlockPos> allMarked = containerManager.positionsInCurrentDimension(mc.level);
        if (allMarked.isEmpty()) {
            stop("playercontrolpp.message.restock.no_containers");
            return;
        }

        // Only now that we have both missing items and marked containers do we cancel the
        // paused builder and take over pathing.
        baritone.cancelPathing();

        BlockPos playerPos = mc.player.blockPosition();
        allMarked.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));
        containerQueue.addAll(allMarked);
        containerIndex = 0;

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.gathering",
                neededItems.size(), containerQueue.size());

        navigateToCurrentContainer(mc);
    }

    /**
     * The Litematica material list is unavailable — most likely the Info HUD is off.
     * Tell the player and stop so the feature does not sit idle while pretending to be active.
     */
    private void cannotReadMaterials(Minecraft mc) {
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_hud");
        stop(null);
    }

    private void navigateToCurrentContainer(Minecraft mc) {
        if (containerIndex >= containerQueue.size()) {
            finishRestockRun(mc);
            return;
        }
        currentContainerTarget = containerQueue.get(containerIndex);
        pathingTicks = 0;
        stuckTicks = 0;
        openRetries = 0;
        dryTicks = 0;
        lastPlayerPos = mc.player.position();
        baritone.pathTo(currentContainerTarget);
        state = State.PATHING;
    }

    private void tickPathing(Minecraft mc) {
        if (mc.player == null) return;
        pathingTicks++;

        if (currentContainerTarget != null
                && mc.player.blockPosition().distSqr(currentContainerTarget) <= PlayerUtil.blockReachSq(mc.player)) {
            baritone.cancelPathing();
            openContainer(mc);
            return;
        }

        if (pathingTicks > PATHING_SETTLE) {
            Vec3 pos = mc.player.position();
            if (pos.distanceToSqr(lastPlayerPos) < STUCK_EPSILON_SQ) {
                stuckTicks++;
                if (stuckTicks > PATHING_STUCK_LIMIT) {
                    baritone.cancelPathing();
                    containerIndex++;
                    navigateToCurrentContainer(mc);
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPlayerPos = pos;
        }

        if (pathingTicks > PATHING_SETTLE && !baritone.isPathing()) {
            stuckTicks = 0;
            // Only attempt to open if we're close enough — isPathing() false could also
            // mean Baritone failed to find a path, in which case the player is far away.
            if (currentContainerTarget != null
                    && mc.player.blockPosition().distSqr(currentContainerTarget) < 36.0) {
                openContainer(mc);
            } else {
                containerIndex++;
                navigateToCurrentContainer(mc);
            }
        }
    }

    private void openContainer(Minecraft mc) {
        if (currentContainerTarget == null) return;
        openCooldown = 0;
        openRetries = 0;
        tryOpenContainerClick(mc);
    }

    /** Send the useItemOn packet without touching retry counters. */
    private void tryOpenContainerClick(Minecraft mc) {
        try {
            Vec3 eye = mc.player.getEyePosition();
            double dx = currentContainerTarget.getX() + 0.5 - eye.x;
            double dy = currentContainerTarget.getY() + 0.5 - eye.y;
            double dz = currentContainerTarget.getZ() + 0.5 - eye.z;
            double distH = Math.sqrt(dx * dx + dz * dz);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setYHeadRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) Math.toDegrees(-Math.atan2(dy, distH)));

            Direction face = ContainerOpener.nearestFace(eye, currentContainerTarget);
            Vec3 hitPos = new Vec3(
                    currentContainerTarget.getX() + 0.5 + face.getStepX() * 0.5,
                    currentContainerTarget.getY() + 0.5 + face.getStepY() * 0.5,
                    currentContainerTarget.getZ() + 0.5 + face.getStepZ() * 0.5);
            BlockHitResult hitResult = new BlockHitResult(hitPos, face, currentContainerTarget, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);

            openCooldown = OPEN_WAIT_TICKS;
            dryTicks = 0;
            state = State.OPENING;
        } catch (Exception e) {
            openRetries++;
            if (openRetries >= MAX_OPEN_RETRIES) {
                containerIndex++;
                navigateToCurrentContainer(mc);
            }
        }
    }

    private void tickOpening(Minecraft mc) {
        if (openCooldown > 0) { openCooldown--; return; }
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>) {
            openRetries = 0;
            transferCooldown = 0;
            dryTicks = 0;
            state = State.TRANSFERRING;
        } else {
            openRetries++;
            if (openRetries < MAX_OPEN_RETRIES) {
                // Retry the open — don't call openContainer (which would count openRetries again).
                openCooldown = 0;
                tryOpenContainerClick(mc);
            } else {
                containerIndex++;
                navigateToCurrentContainer(mc);
            }
        }
    }

    private void tickTransferring(Minecraft mc) {
        if (transferCooldown > 0) return;

        // If the screen has disappeared (server closed it or we did) — move on.
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            mc.player.closeContainer();
            containerIndex++;
            navigateToCurrentContainer(mc);
            return;
        }

        // Inventory full → stop transferring from this container.
        if (isInventoryFull(mc)) {
            mc.player.closeContainer();
            if (!anyTransferredThisRun) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.inventory_full");
                restartBuilder(mc);
            } else {
                finishRestockRun(mc);
            }
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;

        // --- Quick bail-out: nothing useful in this container at all ---
        if (dryTicks == 0 && !containerHasAnyNeededItem(handler, mc)) {
            mc.player.closeContainer();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_no_match");
            containerIndex++;
            navigateToCurrentContainer(mc);
            return;
        }

        boolean transferred = tryTransferOne(mc, handler);

        if (transferred) {
            anyTransferredThisRun = true;
            dryTicks = 0;
            transferCooldown = 2;
        } else {
            dryTicks++;
            if (dryTicks >= MAX_DRY_TICKS) {
                // Nothing moved for several ticks — this container is drained.
                mc.player.closeContainer();
                containerIndex++;
                navigateToCurrentContainer(mc);
            }
        }
    }

    /** @return true if anything was moved. */
    private boolean tryTransferOne(Minecraft mc, AbstractContainerMenu handler) {
        // Phase 1: loose stacks that match our needed items.
        for (Slot slot : handler.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || ItemUtil.isShulkerBox(stack)) continue;

            for (MaterialItemEntry entry : neededItems) {
                if (ItemUtil.is(stack, entry.item)
                        && countInInventory(entry.item, mc) < entry.neededCount) {
                    try {
                        SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
                        return true;
                    } catch (Exception ignored) {}
                }
            }
        }

        // Phase 2: shulker boxes containing needed items (only if config enabled).
        if (Configs.Restocks.RESTOCK_SHULKER_MODE.getBooleanValue()) {
            for (Slot slot : handler.slots) {
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty() || !ItemUtil.isShulkerBox(stack)) continue;

                if (shulkerBoxContainsNeededItem(stack, mc)) {
                    try {
                        SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
                        /*haveShulkerBoxesContainsItems = 10;
                        mc.player.closeContainer();*/

                        return true;
                    } catch (Exception ignored) {}
                }
            }
        }

        return false;
    }

    /** All containers visited (or none marked). Restart the builder. */
    private void finishRestockRun(Minecraft mc) {
        if (anyTransferredThisRun) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.stock_complete");
            dryRestartCount = 0;
        } else {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.nothing_found");
            dryRestartCount++;
            // Consecutive restarts with zero items transferred means materials are permanently
            // unavailable — stop instead of looping forever.
            if (dryRestartCount >= MAX_DRY_RESTARTS) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_progress");
                stop(null);
                return;
            }
        }
        restartBuilder(mc);
    }

    private void restartBuilder(Minecraft mc) {
        baritone.cancelPathing();
        mc.player.closeContainer();
        neededItems.clear();
        containerQueue.clear();
        currentContainerTarget = null;

        if (baritone.startLitematicaBuild(0)) {
            attemptedBuildLaunch = true;
            state = State.RESTARTING;
        } else {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
            active = false;
            state = State.IDLE;
        }
    }

    /** Wait for the restarted builder to become active before resuming monitoring. */
    private void tickRestarting(Minecraft mc) {
        if (baritone.isBuilderActive()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_restarted");
            state = State.MONITORING;
        }
    }

    // ---- Helpers ----

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private int countInInventory(Item item, Minecraft mc) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (ItemUtil.is(stack, item)) count += stack.getCount();
        }
        return count;
    }

    private boolean shulkerBoxContainsNeededItem(ItemStack shulkerBox, Minecraft mc) {
        for (ItemStack inner : ItemUtil.contentsOf(shulkerBox)) {
            for (MaterialItemEntry entry : neededItems) {
                if (ItemUtil.is(inner, entry.item)
                        && countInInventory(entry.item, mc) < entry.neededCount) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One-shot check at the start of TRANSFERRING: does the open container hold anything useful? */
    private boolean containerHasAnyNeededItem(AbstractContainerMenu handler, Minecraft mc) {
        for (Slot slot : handler.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            if (!ItemUtil.isShulkerBox(stack)) {
                for (MaterialItemEntry entry : neededItems) {
                    if (ItemUtil.is(stack, entry.item)
                            && countInInventory(entry.item, mc) < entry.neededCount) {
                        return true;
                    }
                }
            } else if (Configs.Restocks.RESTOCK_SHULKER_MODE.getBooleanValue()
                    && shulkerBoxContainsNeededItem(stack, mc)) {
                return true;
            }
        }
        return false;
    }
}
