package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared mutable state for one auto-gathering run, passed to every module in this package.
 *
 * <p>The fields are public by design: this was extracted from a single 1100-line class and the
 * modules that read and write them are all in this package. Treat it as that class's field block,
 * not as an API.
 */
public class GatherContext {

    public AutoMaterialGatherer.State state = AutoMaterialGatherer.State.IDLE;
    public boolean active;
    public Minecraft client;

    // Material list data
    public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public int currentItemIndex;
    public Item currentTargetItem;
    public int targetNeededTotal;
    public int currentlyGathered;

    // Chest search data
    public final List<BlockPos> foundPositions = new ArrayList<>();
    public int currentPosIndex;
    public int chestRetryCount;

    // Baritone pathing tracking
    public Vec3 lastPlayerPos = Vec3.ZERO;
    public int stuckTicks;
    public int pathingTicks;
    public boolean pathingWasActive;
    public BlockPos currentPathTarget;

    // Container interaction
    public int transferCooldown;
    public boolean containerJustOpened;
    public int openAttemptCount;
    public BlockPos currentContainerTarget;
    public List<BlockPos> adjacentContainerTargets;
    public int adjacentTryIndex;
    public ItemTransferStrategy.TransferPlan currentTransferPlan = ItemTransferStrategy.TransferPlan.NONE;
    public final Map<Item, Integer> stacksTakenThisContainer = new HashMap<>();
    public final Map<Item, Integer> shulkerBoxesTakenThisContainer = new HashMap<>();

    /**
     * Whether the last successful transfer was a whole shulker box. If it was and the inventory
     * then fills up, auto-store must not run — it would put the box straight back.
     */
    public boolean justTookShulkerBox;

    // Running totals for the current item, compared against the plan to avoid over-gathering
    public int totalBoxesTakenForItem;
    public int totalStacksTakenForItem;

    /**
     * @return the item currently being gathered, or {@code null} once the list is exhausted.
     *
     * <p>Bounds-checks {@code currentItemIndex} in one place instead of at every call site.
     */
    public MaterialItemEntry currentItem() {
        return currentItemIndex >= 0 && currentItemIndex < missingItems.size()
                ? missingItems.get(currentItemIndex)
                : null;
    }

    /**
     * Clear everything belonging to one run.
     *
     * <p>{@code active} and {@code client} are deliberately left alone: the caller sets
     * {@code active} around this call, and {@code client} is the Minecraft instance, which does not
     * belong to a run at all.
     */
    public void reset() {
        state = AutoMaterialGatherer.State.IDLE;

        missingItems.clear();
        currentItemIndex = 0;
        currentTargetItem = null;
        targetNeededTotal = 0;
        currentlyGathered = 0;

        foundPositions.clear();
        currentPosIndex = 0;
        chestRetryCount = 0;

        lastPlayerPos = Vec3.ZERO;
        stuckTicks = 0;
        pathingTicks = 0;
        pathingWasActive = false;
        currentPathTarget = null;

        transferCooldown = 0;
        containerJustOpened = false;
        openAttemptCount = 0;
        currentContainerTarget = null;
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;
        currentTransferPlan = ItemTransferStrategy.TransferPlan.NONE;
        stacksTakenThisContainer.clear();
        shulkerBoxesTakenThisContainer.clear();

        justTookShulkerBox = false;
        totalBoxesTakenForItem = 0;
        totalStacksTakenForItem = 0;
    }
}
