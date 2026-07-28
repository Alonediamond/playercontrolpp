package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
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
 * Monitors Baritone's {@code #litematica} builder for material-shortage pauses, then restocks from
 * shulker boxes in the inventory and from player-marked container positions, and lets the build
 * carry on.
 *
 * <h3>Activation</h3>
 * <ul>
 *   <li><b>One-click Build + Restock</b> (hotkey) — toggle. Press once to start the build AND
 *       enable monitoring; press again to stop. The run ends by itself when the schematic is
 *       finished, so the hotkey is only needed to abort early.</li>
 *   <li><b>Mark Container</b> (hotkey) — marks/unmarks the container the player is looking at.
 *       Entries are stored in {@code Configs.Restocks.MARKED_CONTAINERS} and editable from the
 *       config GUI.</li>
 * </ul>
 *
 * <h3>Flow</h3>
 * <pre>
 * MONITORING ──builder finished──────────────────────────────────→ stop
 *      │
 *      └─builder paused→ ANALYZING
 *                          ├─nothing actually missing → promote to hotbar → resume
 *                          ├─shulker box in inventory → SHULKER_OPEN → SHULKER_TAKE ─┐
 *                          └─marked containers        → PATHING → OPENING →          │
 *                                                       TRANSFERRING ────────────────┤
 *                                                                                    ▼
 *                                                                                FINISHING
 *                                                                          (resume or relaunch)
 * </pre>
 *
 * <h3>Why the two "keep building" paths differ</h3>
 * Walking to a marked container means driving Baritone's {@code CustomGoalProcess}, and
 * {@code PathingBehavior.cancelEverything()} sends {@code onLostControl()} to every process —
 * which makes {@code BuilderProcess} drop its schematic entirely. A container trip therefore has
 * to relaunch the build afterwards. Taking materials out of a shulker box needs no movement at
 * all, so that path leaves the paused builder untouched and simply resumes it, keeping the layer
 * progress Baritone had already made.
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
        IDLE, MONITORING, ANALYZING, PATHING, OPENING, TRANSFERRING,
        SHULKER_OPEN, SHULKER_TAKE, FINISHING
    }

    /**
     * One material the run is trying to obtain.
     *
     * @param item   the item to collect
     * @param target how many the player should end up holding <em>loose</em> in the 36 main
     *               inventory slots. Everything is expressed as this target rather than as a
     *               remaining amount, so the decision "do we still need this?" is a fresh look at
     *               the inventory every time instead of a counter that can drift.
     */
    private record Need(Item item, int target) {}

    private State state = State.IDLE;
    private boolean active;

    /** Ticks to sit still before the current state runs again — lets packets and screens settle. */
    private int deferTicks;

    /**
     * True once this run has taken Baritone's builder down to walk somewhere, which means the
     * build has to be relaunched rather than resumed.
     */
    private boolean builderCancelled;

    /**
     * True once this cycle has already toured the marked containers. Opening shulker boxes picked
     * up on that tour leads back through the same "still short?" decision, so without this the two
     * phases would hand off to each other forever.
     */
    private boolean containerTripDone;

    /** Items moved into the inventory during the current restock cycle. */
    private int cycleGained;
    /** Consecutive restock cycles that obtained nothing. Guards against looping forever. */
    private int noGainCycles;

    private final BaritoneIntegration baritone = BaritoneIntegration.getInstance();
    private final LitematicaIntegration litematica = LitematicaIntegration.getInstance();
    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();
    private final MarkedContainerManager containerManager = MarkedContainerManager.getInstance();

    // Restock-run transient state
    /** Materials the player is short of. */
    private final List<Need> needs = new ArrayList<>();
    /** Every material type the schematic still lists as missing, short or not. */
    private final List<Item> schematicWanted = new ArrayList<>();
    private final List<BlockPos> containerQueue = new ArrayList<>();
    /** Inventory slots (0-35) holding a shulker box worth opening. */
    private final List<Integer> shulkerQueue = new ArrayList<>();

    private int containerIndex;
    private BlockPos currentContainerTarget;
    private int pathingTicks;
    private int stuckTicks;
    private int openCooldown;
    private int openRetries;
    private int transferCooldown;
    /** Ticks since we last moved an item out of the open screen. */
    private int dryTicks;
    private boolean tookFromThisContainer;
    private int shulkerOpenWait;
    /** Ticks spent in SHULKER_OPEN waiting for the player's own inventory menu to be the open one. */
    private int shulkerReadyWait;
    /** Unopened shulker boxes taken from marked containers this cycle. */
    private int shulkersTaken;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    private static final int OPEN_WAIT_TICKS = 12;
    private static final int MAX_OPEN_RETRIES = 3;
    /** Consecutive transfer ticks with no item moved before we treat the container as drained. */
    private static final int MAX_DRY_TICKS = 8;
    private static final int PATHING_STUCK_LIMIT = 120;
    private static final int PATHING_SETTLE = 5;
    private static final double STUCK_EPSILON_SQ = 0.04;
    /** Ticks to let the material list and any closing screen settle before analysing. */
    private static final int ANALYZE_SETTLE = 2;
    /** Ticks to let the builder work after a resume/relaunch before believing another pause. */
    private static final int BUILD_SETTLE = 10;
    /** Ticks to wait for QuickShulker to open a box before giving up on it. */
    private static final int SHULKER_OPEN_WAIT = 20;
    /** Ticks to wait for every screen to close before sending a QuickShulker open packet. */
    private static final int SHULKER_READY_WAIT = 40;
    /**
     * Boxes to carry off per cycle. Each one is opened at the end of the trip, and a box counts as
     * "still needed" until then, so without a cap a chest full of shulkers would be emptied into
     * the inventory in a single visit.
     */
    private static final int MAX_SHULKERS_PER_CYCLE = 3;
    private static final int MAX_NO_GAIN_CYCLES = 3;

    // ---- Public API ----

    @Override
    public boolean isActive() { return active; }

    @Override
    public void onClientTick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.player.isDeadOrDying()) {
            stop("playercontrolpp.message.restock.player_died");
            return;
        }

        if (deferTicks > 0) { deferTicks--; return; }
        if (transferCooldown > 0) { transferCooldown--; }

        switch (state) {
            case IDLE -> {} // unreachable while active
            case MONITORING -> tickMonitoring(mc);
            case ANALYZING -> doAnalyze(mc);
            case PATHING -> tickPathing(mc);
            case OPENING -> tickOpening(mc);
            case TRANSFERRING -> tickTransferring(mc);
            case SHULKER_OPEN -> tickShulkerOpen(mc);
            case SHULKER_TAKE -> tickShulkerTake(mc);
            case FINISHING -> tickFinishing(mc);
        }
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

        resetRunState();

        // Leave an already running build alone — the player may have started it with #litematica
        // and only now decided they want restocking on top.
        if (!baritone.isBuilderActive() && !baritone.startLitematicaBuild(0)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
            return;
        }

        active = true;
        state = State.MONITORING;
        deferTicks = BUILD_SETTLE;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.started");
    }

    private void stop(String messageKey) {
        baritone.cancelPathing();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        active = false;
        state = State.IDLE;
        resetRunState();
        if (messageKey != null) {
            MessageUtil.sendActionBar(mc, messageKey);
        }
    }

    /** Clears everything a run accumulates, so a restart never inherits stale counters. */
    private void resetRunState() {
        deferTicks = 0;
        builderCancelled = false;
        containerTripDone = false;
        cycleGained = 0;
        noGainCycles = 0;
        needs.clear();
        schematicWanted.clear();
        containerQueue.clear();
        shulkerQueue.clear();
        containerIndex = 0;
        currentContainerTarget = null;
        pathingTicks = 0;
        stuckTicks = 0;
        openCooldown = 0;
        openRetries = 0;
        transferCooldown = 0;
        dryTicks = 0;
        tookFromThisContainer = false;
        shulkerOpenWait = 0;
        shulkerReadyWait = 0;
        shulkersTaken = 0;
        lastPlayerPos = Vec3.ZERO;
    }

    // ---- Monitoring ----

    private void tickMonitoring(Minecraft mc) {
        // The builder drops its schematic in onLostControl(), which it calls itself right after
        // logging "Done building". So a process that was running and now is not means the
        // schematic is finished (or something else cancelled it) — either way there is nothing
        // left for this feature to do, and sitting here idle is what used to force the player to
        // press the hotkey a second time.
        if (!baritone.isBuilderActive()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_finished");
            stop(null);
            return;
        }

        if (baritone.isBuilderPaused()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.pause_detected");
            state = State.ANALYZING;
            deferTicks = ANALYZE_SETTLE;
        }
    }

    /** Reads Litematica's material list and decides how to satisfy the shortage. */
    private void doAnalyze(Minecraft mc) {
        needs.clear();
        schematicWanted.clear();
        containerQueue.clear();
        shulkerQueue.clear();
        containerIndex = 0;
        cycleGained = 0;
        shulkersTaken = 0;
        containerTripDone = false;

        if (!readMaterialList(mc)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_material_list");
            stop(null);
            return;
        }

        if (needs.isEmpty()) {
            handlePauseWithoutShortage(mc);
            return;
        }

        // Shulker boxes already carried cost no travel and leave the paused builder alive.
        if (shulkerModeEnabled() && quickShulker.isLoaded()) {
            collectShulkerCandidates(mc);
        }
        if (!shulkerQueue.isEmpty()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.shulker_found",
                    shulkerQueue.size());
            state = State.SHULKER_OPEN;
            return;
        }

        startContainerTrip(mc);
    }

    /**
     * The builder paused although the player holds everything Litematica still lists as missing.
     *
     * <p>The usual reason is Baritone's {@code allowInventory} setting, which is off by default and
     * limits the builder to the nine hotbar slots — materials sitting in the main inventory are
     * invisible to it. Promoting one onto a free hotbar slot is enough to unblock the build, and is
     * the difference between "carry on" and the feature stopping while the player can plainly see
     * the materials in their inventory.
     */
    private void handlePauseWithoutShortage(Minecraft mc) {
        int promoted = promoteToHotbar(mc, schematicWanted);
        if (promoted > 0) {
            noGainCycles = 0;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.hotbar_promoted", promoted);
        } else {
            noGainCycles++;
            if (noGainCycles >= MAX_NO_GAIN_CYCLES) {
                MessageUtil.sendActionBar(mc, baritone.allowsInventory()
                        ? "playercontrolpp.message.restock.paused_unreachable"
                        : "playercontrolpp.message.restock.paused_hotbar_full");
                stop(null);
                return;
            }
        }
        resumeOrRelaunch(mc);
    }

    /**
     * Fills {@link #needs} and {@link #schematicWanted} from Litematica's material list.
     *
     * <p>{@code MaterialListEntry.getCountMissing()} counts blocks still missing <em>in the
     * world</em> and is fixed when the list is created — {@code updateAvailableCounts()} only ever
     * refreshes {@code countAvailable}. It is therefore a target, not a remaining amount, and
     * comparing it against an inventory count as if the two were the same unit is what made the
     * feature skip every container while the player was holding plenty of the material.
     *
     * <p>Litematica's own {@code countAvailable} is no help either: it counts items inside shulker
     * boxes and bundles, which Baritone cannot place. The "have" side is counted here instead, over
     * the 36 loose slots the builder actually draws from.
     */
    private boolean readMaterialList(Minecraft mc) {
        try {
            Object materialList = litematica.getMaterialList();
            if (materialList == null) return false;

            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            if (!(allMaterials instanceof List<?> allList) || allList.isEmpty()) return false;

            Set<Object> ignored = litematica.getIgnoredSet(materialList);
            int stacks = Math.max(1, Configs.Restocks.RESTOCK_STACKS_PER_ITEM.getIntegerValue());

            for (Object entry : allList) {
                if (ignored.contains(entry)) continue;

                ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                int countMissing = (Integer) entry.getClass()
                        .getMethod("getCountMissing").invoke(entry);
                if (stack.isEmpty() || countMissing <= 0) continue;

                Item item = stack.getItem();
                schematicWanted.add(item);

                // A whole schematic usually needs far more than an inventory holds, so top up to
                // a few stacks instead of chasing the full figure — otherwise one material fills
                // every slot and the rest never get collected.
                int target = Math.min(countMissing, Math.max(1, stack.getMaxStackSize()) * stacks);
                if (looseCount(mc, item) < target) {
                    needs.add(new Need(item, target));
                }
            }
            return true;

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Auto-restock: failed to read Litematica material list", e);
            return false;
        }
    }

    // ---- Marked containers ----

    private void startContainerTrip(Minecraft mc) {
        List<BlockPos> allMarked = containerManager.positionsInCurrentDimension(mc.level);
        if (allMarked.isEmpty()) {
            stop("playercontrolpp.message.restock.no_containers");
            return;
        }

        // Only now do we take the paused builder down: driving CustomGoalProcess sends
        // onLostControl() to it, which discards the schematic.
        baritone.cancelPathing();
        builderCancelled = true;
        containerTripDone = true;

        BlockPos playerPos = mc.player.blockPosition();
        allMarked.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));
        containerQueue.addAll(allMarked);
        containerIndex = 0;

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.gathering",
                needs.size(), containerQueue.size());

        navigateToCurrentContainer(mc);
    }

    private void navigateToCurrentContainer(Minecraft mc) {
        if (containerIndex >= containerQueue.size()) {
            afterContainers(mc);
            return;
        }
        currentContainerTarget = containerQueue.get(containerIndex);
        pathingTicks = 0;
        stuckTicks = 0;
        openRetries = 0;
        dryTicks = 0;
        tookFromThisContainer = false;
        lastPlayerPos = mc.player.position();
        baritone.pathTo(currentContainerTarget);
        state = State.PATHING;
    }

    private void nextContainer(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        containerIndex++;
        navigateToCurrentContainer(mc);
    }

    /** Every marked container visited. Shulker boxes picked up along the way get opened now. */
    private void afterContainers(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        if (shulkerModeEnabled() && quickShulker.isLoaded() && anythingStillNeeded(mc)) {
            collectShulkerCandidates(mc);
            if (!shulkerQueue.isEmpty()) {
                baritone.cancelPathing();
                state = State.SHULKER_OPEN;
                deferTicks = ANALYZE_SETTLE;
                return;
            }
        }
        endCycle(mc);
    }

    private void tickPathing(Minecraft mc) {
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
                    nextContainer(mc);
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPlayerPos = pos;
        }

        if (pathingTicks > PATHING_SETTLE && !baritone.isPathing()) {
            stuckTicks = 0;
            // isPathing() going false can also mean Baritone found no path at all, in which case
            // the player is still far away and there is nothing to open.
            if (currentContainerTarget != null
                    && mc.player.blockPosition().distSqr(currentContainerTarget) < 36.0) {
                openContainer(mc);
            } else {
                nextContainer(mc);
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
                nextContainer(mc);
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
                // Retry the open — don't call openContainer (which would reset openRetries).
                openCooldown = 0;
                tryOpenContainerClick(mc);
            } else {
                nextContainer(mc);
            }
        }
    }

    private void tickTransferring(Minecraft mc) {
        // Screen gone (server closed it, or we did) — clicking into it would go nowhere.
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            nextContainer(mc);
            return;
        }

        if (isInventoryFull(mc)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.inventory_full");
            endCycle(mc);
            return;
        }

        if (transferCooldown > 0) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (takeOneNeeded(mc, menu) || (shulkerModeEnabled() && takeShulkerBox(mc, menu))) {
            tookFromThisContainer = true;
            dryTicks = 0;
            transferCooldown = 2;
            return;
        }

        if (++dryTicks >= MAX_DRY_TICKS) {
            if (!tookFromThisContainer) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_no_match");
            }
            nextContainer(mc);
        }
    }

    // ---- Shulker boxes in the inventory (QuickShulker) ----

    /** Inventory slots holding a single shulker box that contains something we are short of. */
    private void collectShulkerCandidates(Minecraft mc) {
        shulkerQueue.clear();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack)) continue;
            if (shulkerHoldsSomethingNeeded(mc, stack)) {
                shulkerQueue.add(i);
            }
        }
    }

    private void tickShulkerOpen(Minecraft mc) {
        // QuickShulker's packet carries a slot index that the server resolves against
        // player.containerMenu, so anything other than the player's own inventory menu being open
        // would point it at a completely different slot.
        if (!quickShulker.canOpenFromInventory(mc)) {
            if (++shulkerReadyWait > SHULKER_READY_WAIT) {
                afterShulkers(mc);
                return;
            }
            mc.player.closeContainer();
            deferTicks = ANALYZE_SETTLE;
            return;
        }
        shulkerReadyWait = 0;

        Inventory inv = mc.player.getInventory();
        while (!shulkerQueue.isEmpty()) {
            int slot = shulkerQueue.remove(0);
            ItemStack stack = inv.getItem(slot);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack)
                    || !shulkerHoldsSomethingNeeded(mc, stack)) {
                continue;
            }
            if (quickShulker.openShulkerBox(QuickShulkerIntegration.menuSlotForInventorySlot(slot))) {
                shulkerOpenWait = SHULKER_OPEN_WAIT;
                transferCooldown = 0;
                dryTicks = 0;
                state = State.SHULKER_TAKE;
                return;
            }
        }

        afterShulkers(mc);
    }

    private void tickShulkerTake(Minecraft mc) {
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            if (--shulkerOpenWait <= 0) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.shulker_open_failed");
                state = State.SHULKER_OPEN;
            }
            return;
        }

        if (transferCooldown > 0) return;

        if (isInventoryFull(mc)) {
            mc.player.closeContainer();
            shulkerQueue.clear();
            state = State.SHULKER_OPEN;
            deferTicks = ANALYZE_SETTLE;
            return;
        }

        if (takeOneNeeded(mc, mc.player.containerMenu)) {
            dryTicks = 0;
            transferCooldown = 2;
            return;
        }

        if (++dryTicks >= MAX_DRY_TICKS) {
            mc.player.closeContainer();
            state = State.SHULKER_OPEN;
            deferTicks = ANALYZE_SETTLE;
        }
    }

    private void afterShulkers(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        // Still short and there are containers we have not toured yet? Do the trip; otherwise wrap
        // up — going round again would just bounce between the two phases.
        if (!containerTripDone && anythingStillNeeded(mc)
                && !containerManager.positionsInCurrentDimension(mc.level).isEmpty()) {
            startContainerTrip(mc);
            return;
        }
        endCycle(mc);
    }

    // ---- Cycle end ----

    /** One restock cycle is over: report, guard against looping, then get the build going again. */
    private void endCycle(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }

        if (cycleGained > 0) {
            noGainCycles = 0;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.stock_complete");
        } else {
            noGainCycles++;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.nothing_found");
            if (noGainCycles >= MAX_NO_GAIN_CYCLES) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_progress");
                stop(null);
                return;
            }
        }

        state = State.FINISHING;
        deferTicks = ANALYZE_SETTLE + 1;
    }

    /**
     * Runs a tick or two after the last container closed, so the inventory the promotion looks at
     * is the one the server agrees with.
     */
    private void tickFinishing(Minecraft mc) {
        promoteToHotbar(mc, schematicWanted);
        resumeOrRelaunch(mc);
    }

    /** Resume the paused builder when it survived the restock, otherwise start it over. */
    private void resumeOrRelaunch(Minecraft mc) {
        if (!builderCancelled && baritone.isBuilderActive() && baritone.resumeBuilder()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_resumed");
            state = State.MONITORING;
            deferTicks = BUILD_SETTLE;
            return;
        }
        relaunchBuilder(mc);
    }

    private void relaunchBuilder(Minecraft mc) {
        baritone.cancelPathing();
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        currentContainerTarget = null;
        containerQueue.clear();
        shulkerQueue.clear();

        if (!baritone.startLitematicaBuild(0)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
            stop(null);
            return;
        }

        builderCancelled = false;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_restarted");
        // Straight back to monitoring: startLitematicaBuild only reports success once the process
        // is actually running, and if the schematic turns out to be complete the builder goes
        // inactive again on its own and monitoring reports that as finished.
        state = State.MONITORING;
        deferTicks = BUILD_SETTLE;
    }

    // ---- Transfers ----

    /**
     * Moves one stack of the material the player is furthest short of out of the open screen.
     *
     * <p>Picking the least-satisfied material each time round-robins across the shortage instead of
     * letting the first type in the list fill every free slot.
     *
     * @return true when the inventory really gained something.
     */
    private boolean takeOneNeeded(Minecraft mc, AbstractContainerMenu menu) {
        Slot best = null;
        Item bestItem = null;
        double bestRatio = Double.MAX_VALUE;

        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            // A shulker box with something in it is storage, handled by takeShulkerBox(). An empty
            // one is just another building material.
            if (ItemUtil.isShulkerBox(stack) && !ItemUtil.contentsOf(stack).isEmpty()) continue;

            for (Need need : needs) {
                if (!ItemUtil.is(stack, need.item())) continue;
                int have = looseCount(mc, need.item());
                if (have < need.target()) {
                    double ratio = (double) have / need.target();
                    if (ratio < bestRatio) {
                        bestRatio = ratio;
                        best = slot;
                        bestItem = need.item();
                    }
                }
                break;
            }
        }

        if (best == null) return false;

        try {
            int before = looseCount(mc, bestItem);
            SlotActionCompat.quickMove(mc, menu.containerId, best.index);
            // handleContainerInput applies the move to the client menu synchronously, so this is a
            // real confirmation rather than an assumption that the click landed.
            if (looseCount(mc, bestItem) > before) {
                cycleGained++;
                return true;
            }
        } catch (Exception ignored) {
            // Slot vanished between the scan and the click; the dry-tick counter handles it.
        }
        return false;
    }

    /**
     * Takes a whole shulker box that holds a needed material out of the open container. The box is
     * opened later, once the player is standing still again and no other screen is in the way.
     */
    private boolean takeShulkerBox(Minecraft mc, AbstractContainerMenu menu) {
        if (shulkersTaken >= MAX_SHULKERS_PER_CYCLE) return false;

        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtil.isShulkerBox(stack)) continue;
            if (!shulkerHoldsSomethingNeeded(mc, stack)) continue;

            try {
                SlotActionCompat.quickMove(mc, menu.containerId, slot.index);
                shulkersTaken++;
                cycleGained++;
                return true;
            } catch (Exception ignored) {
                // Same as above — treated as a dry tick.
            }
        }
        return false;
    }

    /**
     * Moves needed materials from the main inventory onto free hotbar slots.
     *
     * <p>Only empty hotbar slots are used: evicting whatever the player keeps on the hotbar could
     * take away the pickaxe Baritone needs to clear blocks, which would trade one stall for
     * another.
     *
     * @return how many stacks were promoted.
     */
    private int promoteToHotbar(Minecraft mc, List<Item> wanted) {
        if (wanted.isEmpty() || mc.player == null) return 0;
        // Slot indices are only meaningful against the menu the server has open.
        if (mc.player.containerMenu != mc.player.inventoryMenu) return 0;

        Inventory inv = mc.player.getInventory();
        int moved = 0;

        for (Item item : wanted) {
            if (hotbarCount(inv, item) > 0) continue;

            int source = -1;
            for (int i = PlayerUtil.HOTBAR_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
                if (ItemUtil.is(inv.getItem(i), item)) { source = i; break; }
            }
            if (source < 0) continue;

            int target = -1;
            for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
                if (inv.getItem(i).isEmpty()) { target = i; break; }
            }
            if (target < 0) break; // hotbar full — nothing more we can do without evicting

            // In InventoryMenu space the main inventory keeps its indices, so source doubles as
            // the screen slot; the swap button is the hotbar index itself.
            SlotActionCompat.swapWithHotbar(mc, mc.player.inventoryMenu.containerId, source, target);
            moved++;
        }

        return moved;
    }

    // ---- Helpers ----

    private static boolean shulkerModeEnabled() {
        return Configs.Restocks.RESTOCK_SHULKER_MODE.getBooleanValue();
    }

    private boolean anythingStillNeeded(Minecraft mc) {
        for (Need need : needs) {
            if (looseCount(mc, need.item()) < need.target()) return true;
        }
        return false;
    }

    private boolean shulkerHoldsSomethingNeeded(Minecraft mc, ItemStack shulkerBox) {
        for (ItemStack inner : ItemUtil.contentsOf(shulkerBox)) {
            for (Need need : needs) {
                if (ItemUtil.is(inner, need.item()) && looseCount(mc, need.item()) < need.target()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many of {@code item} sit loose in the 36 slots Baritone builds from.
     *
     * <p>Deliberately not Litematica's {@code countAvailable}, which folds in shulker box and
     * bundle contents — a box full of stone would read as plenty of stone while the builder still
     * has nothing it can place.
     */
    private int looseCount(Minecraft mc, Item item) {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemUtil.is(stack, item)) count += stack.getCount();
        }
        return count;
    }

    private static int hotbarCount(Inventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemUtil.is(stack, item)) count += stack.getCount();
        }
        return count;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }
}
