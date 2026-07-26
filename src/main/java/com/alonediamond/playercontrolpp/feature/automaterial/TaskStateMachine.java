package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.util.MessageUtil;

/**
 * Drives the 11-state task machine for auto material gathering.
 * Orchestrates calls to the specialized modules based on the current state.
 * Also manages the ShulkerBoxStorage sub-system for auto-storing when inventory is full.
 */
public class TaskStateMachine {

    private final GatherContext ctx;
    private final MaterialAnalyzer materialAnalyzer;
    private final ContainerSearcher containerSearcher;
    private final BaritonePathingController pathingController;
    private final ContainerOpener containerOpener;
    private final ItemTransferExecutor transferExecutor;
    private final ShulkerBoxStorage shulkerStorage;

    // When true, the next tick(s) will process the post-storage sync-and-verify
    private boolean pendingStorageDone;
    // Ticks to wait after shulker storage DONE for server-to-client inventory NBT sync
    private int storageSyncTicks;

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
                break;
            case NEXT_ITEM:
                break;
            case COMPLETED:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.completed");
                ctx.active = false;
                pathingController.cancelPathing();
                break;
            case FAILED:
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.pathing_stuck");
                pathingController.cancelPathing();
                containerOpener.closeAnyContainer(ctx.client);
                ctx.adjacentContainerTargets = null;
                ctx.adjacentTryIndex = 0;
                break;
            case STOPPED:
                ctx.active = false;
                pathingController.cancelPathing();
                containerOpener.closeAnyContainer(ctx.client);
                break;
            default:
                break;
        }
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
            if (storageSyncTicks < 15) {
                storageSyncTicks++;
                return;
            }
            pendingStorageDone = false;
            storageSyncTicks = 0;

            boolean satisfied = transferExecutor.isCurrentItemSatisfied(ctx);
            if (satisfied) {
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

        // --- Handle transfer cooldown and container opening retry ---
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
                // Handled by transferCooldown mechanism
                break;

            case FAILED:
                skipCurrentItem();
                break;

            case STOPPED:
                break;

            default:
                break;
        }
    }

    /**
     * Called when inventory is detected to be full.
     * If auto-store-to-shulker is enabled, starts the shulker box storage process.
     * Otherwise stops the auto-gatherer with an inventory-full message.
     */
    public void onInventoryFull() {

        if (ctx.justTookShulkerBox) {
            ctx.justTookShulkerBox = false;
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.inventory_full");
            setState(State.STOPPED);
            return;
        }

        if (ShulkerBoxStorage.isEnabled()) {
            pathingController.cancelPathing();
            containerOpener.closeAnyContainer(ctx.client);

            boolean started = shulkerStorage.startStorage(ctx);
            if (started) {
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
