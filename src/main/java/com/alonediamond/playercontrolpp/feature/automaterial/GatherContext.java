package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Shared mutable context for the auto material gathering process.
 * Holds all state that was previously fields in AutoMaterialGatherer.
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

    // Tracks whether the last successful transfer was a whole shulker box.
    // If true and inventory becomes full, skip auto-store-to-shulker.
    public boolean justTookShulkerBox;

    // Global counters for the current item — compare against TransferPlan to avoid over-gathering
    public int totalBoxesTakenForItem;
    public int totalStacksTakenForItem;

    public void reset() {
        state = AutoMaterialGatherer.State.IDLE;
        chestRetryCount = 0;
        stuckTicks = 0;
        pathingTicks = 0;
        pathingWasActive = false;
        currentPathTarget = null;
        missingItems.clear();
        foundPositions.clear();
        currentItemIndex = 0;
        currentPosIndex = 0;
        currentTargetItem = null;
        currentlyGathered = 0;
        targetNeededTotal = 0;
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
