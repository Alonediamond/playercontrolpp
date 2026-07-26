package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.util.MessageUtil;

/**
 * Drives the auto-gathering state machine, dispatching each state to the module that owns it and
 * running the ShulkerBoxStorage sub-machine when the inventory fills up.
 */
public class TaskStateMachine {

    /**
     * Ticks to wait after shulker storage finishes, so the server's inventory update has arrived
     * before we count what is actually held.
     */
    private static final int STORAGE_SYNC_TICKS = 15;

    private final GatherContext ctx;
    private final MaterialAnalyzer materialAnalyzer;
    private final ContainerSearcher containerSearcher;
    private final BaritonePathingController pathingController;
    private final ContainerOpener containerOpener;
    private final ItemTransferExecutor transferExecutor;
    private final ShulkerBoxStorage shulkerStorage;

    /** True while the post-storage sync-and-verify is pending. */
    private boolean pendingStorageDone;
    private int storageSyncTicks;
    /** Fallback when a FAILED transition does not say why. */
    private static final String DEFAULT_FAILURE_KEY = "playercontrolpp.message.baritone.pathing_stuck";

    public TaskStateMachine(GatherContext ctx,
                            MaterialAnalyzer materialAnalyzer,
                            ContainerSearcher containerSearcher,
                            BaritonePathingController pathingController,
                            ContainerOpener containerOpener,
                            ItemTransferExecutor transferExecutor,
                            ShulkerBoxStorage shulkerStorage) {
        this.ctx = ctx;
        this.materialAnalyzer = materialAnalyzer;
        this.containerSearcher = containerSearcher;
        this.pathingController = pathingController;
        this.containerOpener = containerOpener;
        this.transferExecutor = transferExecutor;
        this.shulkerStorage = shulkerStorage;
    }

    public void setState(State newState) {
        setState(newState, null);
    }

    /**
     * @param reasonKey lang key explaining a FAILED transition. FAILED is where every kind of
     *                  single-item failure converges — container would not open, contents did not
     *                  match, search found nothing — but it used to always report "pathing stuck",
     *                  sending users to debug Baritone over an unrelated problem.
     */
    public void setState(State newState, String reasonKey) {
        ctx.state = newState;
        switch (newState) {
            case ANALYZING:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.analyzing");
                break;
            case SEARCHING:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.searching");
                break;
            case PATHING:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.pathing");
                break;
            case OPENING_CONTAINER:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.opening");
                break;
            case TRANSFERRING_ITEM:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.transferring");
                break;
            case VERIFYING:
            case NEXT_ITEM:
                break;
            case COMPLETED:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.completed");
                ctx.active = false;
                stopEverything();
                break;
            case FAILED:
                MessageUtil.sendActionBar(ctx.client,
                        reasonKey != null ? reasonKey : DEFAULT_FAILURE_KEY);
                stopEverything();
                ctx.adjacentContainerTargets = null;
                ctx.adjacentTryIndex = 0;
                break;
            case STOPPED:
                ctx.active = false;
                stopEverything();
                break;
            default:
                break;
        }
    }

    /** Cancel pathing, close any container, and drop every simulated key hold. */
    private void stopEverything() {
        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);
        containerOpener.releaseKeys();
        shulkerStorage.releaseKeys();
    }

    public void tick() {
        if (!ctx.active || ctx.client.player == null || ctx.client.player.isDeadOrDying()) {
            if (ctx.active) {
                setState(State.STOPPED);
            }
            return;
        }

        // --- Post-storage sync-and-verify (runs independently of isActive()) ---
        if (pendingStorageDone) {
            if (storageSyncTicks < STORAGE_SYNC_TICKS) {
                storageSyncTicks++;
                return;
            }
            pendingStorageDone = false;
            storageSyncTicks = 0;

            if (transferExecutor.isCurrentItemSatisfied(ctx)) {
                ctx.currentItemIndex++;
                setState(State.NEXT_ITEM);
            } else {
                setState(State.SEARCHING);
            }
            return;
        }

        // --- Shulker box storage sub-system ---
        if (shulkerStorage.isActive()) {
            ShulkerBoxStorage.StorageResult result = shulkerStorage.tick(ctx);
            if (result == ShulkerBoxStorage.StorageResult.DONE) {
                pendingStorageDone = true;
                storageSyncTicks = 0;
            } else if (result == ShulkerBoxStorage.StorageResult.FAILED) {
                shulkerStorage.cancel(ctx.client);
                setState(State.STOPPED);
            }
            return;
        }

        // --- Container open cooldown ---
        if (ctx.transferCooldown > 0) {
            ctx.transferCooldown--;
            if (ctx.containerJustOpened && ctx.transferCooldown <= 0) {
                ctx.containerJustOpened = false;
                containerOpener.checkOpenResult(ctx, this, pathingController);
            }
        }

        switch (ctx.state) {
            case IDLE:
                if (ctx.active) {
                    setState(State.ANALYZING);
                }
                break;

            case ANALYZING:
                materialAnalyzer.analyze(ctx, this);
                break;

            case SEARCHING:
                containerSearcher.search(ctx, this, containerOpener, pathingController);
                break;

            case PATHING:
                pathingController.checkProgress(ctx, this, containerOpener);
                break;

            case TRANSFERRING_ITEM:
                transferExecutor.transfer(ctx, this);
                break;

            case VERIFYING:
                transferExecutor.verify(ctx, this, containerOpener, pathingController);
                break;

            case NEXT_ITEM:
                transferExecutor.nextItem(ctx, this);
                break;

            case OPENING_CONTAINER:
                // Driven by the transferCooldown mechanism above.
                break;

            case FAILED:
                skipCurrentItem();
                break;

            case STOPPED:
            default:
                break;
        }
    }

    /**
     * Called when the inventory is detected full. Hands over to shulker storage if the user
     * enabled it, otherwise stops with an inventory-full message.
     */
    public void onInventoryFull() {
        // A whole shulker box was just taken, so storing into one would immediately undo it.
        if (ctx.justTookShulkerBox) {
            ctx.justTookShulkerBox = false;
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.inventory_full");
            setState(State.STOPPED);
            return;
        }

        if (ShulkerBoxStorage.isEnabled()) {
            pathingController.cancelPathing();
            containerOpener.closeAnyContainer(ctx.client);
            if (shulkerStorage.startStorage(ctx)) {
                return;
            }
        }
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.inventory_full");
        setState(State.STOPPED);
    }

    public void skipCurrentItem() {
        ctx.currentItemIndex++;
        setState(State.NEXT_ITEM);
    }
}
