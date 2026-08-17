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
 * 一次自动备货运行的共享可变状态，传给本包内每个模块。
 *
 * <p>字段刻意是 public：它是从一个 1100 行的大类里拆出来的，读写它们的模块全在本包内。
 * 请把它当成那个类的字段块，而不是一套 API。
 */
public class GatherContext {

    public AutoMaterialGatherer.State state = AutoMaterialGatherer.State.IDLE;
    public boolean active;
    public Minecraft client;

    // 材料清单数据
    public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public int currentItemIndex;
    public Item currentTargetItem;
    public int targetNeededTotal;
    public int currentlyGathered;

    // 箱子搜索数据
    public final List<BlockPos> foundPositions = new ArrayList<>();
    public int currentPosIndex;
    public int chestRetryCount;

    // Baritone 寻路追踪
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
     * 上一次成功转移的是否是一整个潜影盒。如果是，紧接着背包满了也不能触发自动存盒——
     * 那会把刚拿的盒子原样存回去。
     */
    public boolean justTookShulkerBox;

    // 当前物品的累计数量，与计划比对以避免超量收集
    public int totalBoxesTakenForItem;
    public int totalStacksTakenForItem;

    /**
     * @return 当前正在收集的物品；清单走完后返回 {@code null}。
     *
     * <p>把 {@code currentItemIndex} 的边界检查收在一处，而不是每个调用点各写一遍。
     */
    public MaterialItemEntry currentItem() {
        return currentItemIndex >= 0 && currentItemIndex < missingItems.size()
                ? missingItems.get(currentItemIndex)
                : null;
    }

    /**
     * 清掉属于一次运行的所有状态。
     *
     * <p>{@code active} 和 {@code client} 刻意不动：{@code active} 由调用方在这个调用前后设置，
     * 而 {@code client} 是 Minecraft 实例，根本不属于某一次运行。
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
