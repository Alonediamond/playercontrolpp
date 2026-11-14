package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialListReader;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ItemTransferExecutor;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerSearcher;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.navigation.BaritonePathingController;
import com.alonediamond.playercontrolpp.feature.automaterial.shulker.ShulkerBoxAccess;
import com.alonediamond.playercontrolpp.feature.automaterial.shulker.MixedBoxReturn;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.RawMaterialTask;
import com.alonediamond.playercontrolpp.feature.automaterial.restock.AutoRestockFeature;

import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import com.alonediamond.playercontrolpp.feature.ContainerHighlightFeature;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualMaterialQueue;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualPreparationMode;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualRawMaterialPlan;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.MaterialSource;
import com.alonediamond.playercontrolpp.config.RawMaterialMode;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import net.minecraft.world.item.ItemStack;
import com.alonediamond.playercontrolpp.Playercontrolpp;

/** 自动备货入口和主状态机；生命周期、状态转换以及子任务衔接由本类统一拥有。 */
public final class AutoMaterialGatherer implements ClientFeature {
    private static final AutoMaterialGatherer INSTANCE = new AutoMaterialGatherer();
    public enum State {
        IDLE, ANALYZING, SEARCHING, PATHING, OPENING_CONTAINER,
        TRANSFERRING_ITEM, VERIFYING, NEXT_ITEM, COMPLETED, FAILED, STOPPED
    }
    private State state = State.IDLE;
    private boolean active;
    private boolean manualRun;
    private ManualPreparationMode manualPreparationMode = ManualPreparationMode.DIRECT;
    private ManualRawMaterialPlan rawPreparation;
    private boolean preparingRaw;
    private java.util.List<MaterialItemEntry> rawPreparationTargets = java.util.List.of();
    private final ManualMaterialQueue manualQueue =
            ManualMaterialQueue.getInstance();
    private int currentItemIndex;
    private int requiredStorageStartCount = -1;
    /**
     * 存盒结束后再等几 tick，让服务端的物品栏更新先到，再去数手上真正有多少。
     */
    private static final int STORAGE_SYNC_TICKS = 15;

    private final GatherContext ctx;
    private final MaterialListReader materialReader = new MaterialListReader();
    private final ContainerSearcher containerSearcher;
    private final BaritonePathingController pathingController;
    private final ContainerOpener containerOpener;
    private final ItemTransferExecutor transferExecutor;
    private final ShulkerBoxAccess shulkerAccess;
    private final RawMaterialTask rawMaterialTask;
    /** 追溯的两个前提只在第一次用到时检查一次，之后沿用结论。 */
    private boolean rawMaterialPrereqChecked;
    private boolean rawMaterialPrereqOk;
    /** 追溯失败后要先把这轮收集到的原材料存进潜影盒。 */
    private boolean pendingTraceStorage;
    /** 这趟存盒是被"追溯失败"触发的：存完之后直接推进到下一个物品，不要拿旧目标重判。 */
    private boolean resumeNextItemAfterStorage;
    /** 这趟存盒只是"顺手收一下"：找不到盒子时跳过即可，不要停掉整轮备货。 */
    private boolean voluntaryStorage;
    private boolean initialStorageDone;

    /** 存盒后的「同步并核对」还没做完时为 true。 */
    private boolean pendingStorageDone;
    private int storageSyncTicks;
    /** FAILED 转换没给原因时的兜底文案。 */
    private static final String DEFAULT_FAILURE_KEY = "playercontrolpp.message.baritone.pathing_stuck";

    private AutoMaterialGatherer() {
        ctx = new GatherContext();
        containerSearcher = new ContainerSearcher();
        pathingController = new BaritonePathingController(BaritoneIntegration.getInstance());
        containerOpener = new ContainerOpener();
        shulkerAccess = new ShulkerBoxAccess();
        rawMaterialTask = new RawMaterialTask(shulkerAccess);
        transferExecutor = new ItemTransferExecutor(shulkerAccess);
    }

    public static AutoMaterialGatherer getInstance() { return INSTANCE; }

    public State getState() { return state; }
    public ManualPreparationMode manualPreparationMode() { return manualPreparationMode; }

    @Override
    public boolean isActive() { return active; }

    public boolean toggle() {
        if (active) {
            stop();
            return false;
        } else {
            return start();
        }
    }

    private boolean start() { return start(false, false, ManualPreparationMode.DIRECT); }

    public boolean startManualQueue() { return startManualQueue(ManualPreparationMode.DIRECT); }
    public boolean startManualQueue(ManualPreparationMode mode) { return start(true, false, mode); }
    public boolean resumeManualQueue() { return start(true, true, manualPreparationMode); }

    private boolean start(boolean manual, boolean resume, ManualPreparationMode preparationMode) {
        ctx.client = Minecraft.getInstance();
        if (ctx.client.player == null || ctx.client.level == null) return false;

        if (active) return false;
        if (!resume && manualQueue.state() == ManualMaterialQueue.State.PAUSED) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.manual_paused");
            return false;
        }
        if (manual && preparationMode != ManualPreparationMode.DIRECT && !LitematListIntegration.getInstance().isLoaded()) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.manual_raw_missing");
            return false;
        }
        if ((!manual && !LitematicaIntegration.getInstance().isLoaded())
                || !BaritoneIntegration.getInstance().isLoaded() || !ChestTrackerIntegration.getInstance().isLoaded()) {
            MessageUtil.sendActionBar(ctx.client, manual ? "playercontrolpp.message.baritone.manual_mods_missing"
                    : "playercontrolpp.message.baritone.mods_missing");
            return false;
        }

        if (AutoRestockFeature.getInstance().isActive()
                || com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature.isEnabled()
                || com.alonediamond.playercontrolpp.feature.SchematicSelectionContainerCacheFeature.isActive()) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.busy");
            return false;
        }
        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);

        resetRunState();
        ctx.reset();
        manualRun = manual;
        if (!resume) {
            manualPreparationMode = preparationMode;
            clearRawPreparation();
        }
        if (manual && !(resume ? manualQueue.resume(ctx::countHeld) : manualQueue.start(ctx::countHeld))) {
            MessageUtil.sendActionBar(ctx.client, manualQueue.state() == ManualMaterialQueue.State.PAUSED
                    ? "playercontrolpp.message.baritone.manual_paused" : "playercontrolpp.message.baritone.manual_empty");
            return false;
        }
        active = true;
        state = State.IDLE;

        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.started");
        return true;
    }

    public void stop() {
        if (manualRun) manualQueue.stop();
        clearRawPreparation();
        setState(State.STOPPED);
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.stopped");
    }

    @Override
    public void onClientTick(Minecraft mc) {
        ctx.client = mc;
        if (active) {
            BlockPos target = rawMaterialTask.targetPosition();
            if (target == null && !rawMaterialTask.ownsFlow()) {
                if (state == State.PATHING && ctx.currentPosIndex < ctx.foundPositions.size())
                    target = ctx.foundPositions.get(ctx.currentPosIndex);
                else target = ctx.currentContainerTarget;
            }
            ContainerHighlightFeature.getInstance().setGatherTarget(target);
        }
        try {
            tickTask();
        } catch (Exception e) {
            com.alonediamond.playercontrolpp.Playercontrolpp.LOGGER.error("Auto material task failed", e);
            setState(State.STOPPED, "playercontrolpp.message.baritone.transfer_interrupted");
        }
    }

    @Override
    public void onWorldChange() {
        com.alonediamond.playercontrolpp.compat.CraftingRecipes.clearCache();
        manualQueue.stop();
        clearRawPreparation();
        if (active) {
            stop();
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.world_change");
        }
    }

    public static boolean areAllThreeModsPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return (loader.isModLoaded("zbaritone")||loader.isModLoaded("baritone-meteor")||loader.isModLoaded("baritone"))
                && loader.isModLoaded("litematica")
                && loader.isModLoaded("chesttracker");
    }

    private void analyze() {
        try {
            if (manualRun) {
                var batch = manualQueue.activeBatch();
                if (batch == null) { setState(State.STOPPED); return; }
                if (manualPreparationMode != ManualPreparationMode.DIRECT && rawPreparation == null) {
                    var definition = manualQueue.batches().get(batch.index());
                    ManualRawMaterialPlan analyzed = ManualRawMaterialPlan.analyze(definition);
                    if (manualPreparationMode == ManualPreparationMode.RAW_THEN_CRAFT && analyzed.unsupportedRecipe()) {
                        setState(State.STOPPED, "playercontrolpp.message.baritone.manual_raw_unsupported");
                        return;
                    }
                    boolean rawOnly = manualPreparationMode == ManualPreparationMode.RAW_ONLY;
                    java.util.List<MaterialItemEntry> targets = java.util.List.of();
                    if (rawOnly) {
                        var reserves = ManualRawMaterialPlan.reserves(definition, batch.targets(), manualQueue.completedTargets());
                        targets = analyzed.rawOnlyTargets(reserves, ctx::countHeld);
                        manualQueue.replaceActiveTargets(targets);
                    }
                    // 分析与数量校验全部成功后才发布，恢复时不能把半成品计划当成完成的快照。
                    rawPreparation = analyzed;
                    rawPreparationTargets = targets;
                    preparingRaw = rawOnly;
                }
                ctx.requirements.replaceWith(preparingRaw ? rawPreparationTargets : batch.targets());
                currentItemIndex = 0;
                if (!startInitialStorage()) setState(State.NEXT_ITEM);
                return;
            }
            var result = materialReader.read(ctx.client.player);
            if (result.error() != null) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone." + result.error());
                setState(State.STOPPED);
                return;
            }
            ctx.requirements.replaceWith(result.items());
            currentItemIndex = 0;
            if (startInitialStorage()) return;
            // NEXT_ITEM 会跳过已满足条目，满包且材料已齐也能正常结束。
            setState(State.NEXT_ITEM);
        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Unable to read material requirements", e);
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.analyze_error", e.toString());
            setState(State.STOPPED);
        }
    }

    private boolean isCurrentItemSatisfied() {
        if (ctx.targetItem() == null) return false;
        return ctx.countHeld(ctx.targetItem()) >= ctx.targetTotal();
    }

    /** 换到下一个缺失物品，或者结束。 */
    private void nextItem() {
        transferExecutor.reset();

        // 开箱时可能顺带取齐其他条目，直接跳过它们，避免重复搜索。
        while (currentItemIndex < ctx.requirements.entries().size()) {
            MaterialItemEntry candidate = ctx.requirements.entries().get(currentItemIndex);
            if (ctx.countHeld(candidate.item) < candidate.neededCount) break;
            currentItemIndex++;
        }

        if (currentItemIndex >= ctx.requirements.entries().size()) {
            if (manualRun && completeManualBatch()) return;
            setState(State.COMPLETED);
            return;
        }

        MaterialItemEntry entry = ctx.requirements.entries().get(currentItemIndex);
        ctx.setTarget(entry.item, entry.neededCount);
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.foundPositions.clear();
        // 对上一件物品空手的容器，对这一件可能还有货。
        ctx.exhaustedPositions.clear();

        if (ctx.countHeld(ctx.targetItem()) >= ctx.targetTotal()) {
            onCurrentTargetSatisfied();
            return;
        }

        if (manualRun && manualPreparationMode == ManualPreparationMode.RAW_THEN_CRAFT
                && !preparingRaw && tryStartRawMaterialTask()) return;
        setState(State.SEARCHING);
    }

    /** 只有整批满足才推进；失败批次保留原目标，供玩家补充缓存后恢复。 */
    private boolean completeManualBatch() {
        boolean missing = ctx.requirements.entries().stream().anyMatch(entry -> ctx.countHeld(entry.item) < entry.neededCount);
        if (missing) {
            manualQueue.failActiveBatch("incomplete");
            setState(State.STOPPED, "playercontrolpp.message.baritone.manual_paused");
            return true;
        }
        boolean nextReady = manualQueue.completeActiveBatch(ctx::countHeld);
        if (!nextReady && manualQueue.state() == ManualMaterialQueue.State.RUNNING) {
            manualQueue.failActiveBatch("incomplete");
            setState(State.STOPPED, "playercontrolpp.message.baritone.manual_paused");
            return true;
        }
        clearRawPreparation();
        if (manualQueue.state() == ManualMaterialQueue.State.PAUSED) {
            setState(State.STOPPED, "playercontrolpp.message.baritone.manual_paused");
            return true;
        }
        if (manualQueue.activeBatch() == null) return false;
        resetRunState();
        ctx.reset();
        setState(State.ANALYZING);
        return true;
    }

    private void clearRawPreparation() {
        rawPreparation = null;
        rawPreparationTargets = java.util.List.of();
        preparingRaw = false;
    }

    /** 关闭容器后重新数一遍，然后决定推进还是换下一个位置。 */
    private void verifyTransfer() {

        if (ctx.countHeld(ctx.targetItem()) >= ctx.targetTotal()) {
            onCurrentTargetSatisfied();
            return;
        }

        ctx.currentPosIndex++;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;

        if (ctx.currentPosIndex >= ctx.foundPositions.size()) {
            setState(State.SEARCHING);
        } else {
            pathingController.navigateTo(ctx.foundPositions.get(ctx.currentPosIndex), ctx, this, containerOpener);
        }
    }

    public void setState(State newState) {
        setState(newState, null);
    }

    /** 每次开始新一轮备货时调用：清掉只在单轮内有效的判断与中间状态。 */
    private void resetRunState() {
        requiredStorageStartCount = -1;
        initialStorageDone = false;
        transferExecutor.reset();
        rawMaterialPrereqChecked = false;
        rawMaterialPrereqOk = false;
        pendingTraceStorage = false;
        resumeNextItemAfterStorage = false;
        voluntaryStorage = false;
        pendingStorageDone = false;
        storageSyncTicks = 0;
        rawMaterialTask.cancel(ctx.client);
        clearMixedBoxReturn();
        ctx.mixedBoxReturnClosing = false;
    }

    /**
     * @param reasonKey 解释这次 FAILED 的语言键。FAILED 是所有单项失败的汇合处——容器打不开、
     *                  内容不匹配、搜索没结果——但早先它一律报「寻路卡住」，
     *                  把用户引去排查毫不相干的 Baritone。
     */
    public void setState(State newState, String reasonKey) {
        state = newState;
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
                long missing = ctx.requirements.entries().stream().filter(entry -> ctx.countHeld(entry.item) < entry.neededCount).count();
                if (missing > 0) MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.incomplete", missing);
                else MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.completed");
                active = false;
                stopEverythingAndTasks();
                break;
            case FAILED:
                MessageUtil.sendActionBar(ctx.client,
                        reasonKey != null ? reasonKey : DEFAULT_FAILURE_KEY);
                stopEverything();
                ctx.adjacentContainerTargets = null;
                ctx.adjacentTryIndex = 0;
                break;
            case STOPPED:
                if (manualRun && manualQueue.state() ==
                        ManualMaterialQueue.State.RUNNING)
                    manualQueue.failActiveBatch(reasonKey);
                if (reasonKey != null) MessageUtil.sendActionBar(ctx.client, reasonKey);
                active = false;
                stopEverythingAndTasks();
                break;
            default:
                break;
        }
    }

    /** 取消寻路、关掉所有容器、松开全部模拟按键。单项失败（FAILED）时用这个，不影响正在跑的追溯任务。 */
    private void stopEverything() {
        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);
        containerOpener.releaseKeys();
        shulkerAccess.releaseKeys();
    }

    /** 整轮备货结束时的收尾：额外取消追溯任务（它可能正开着合成界面或让 Baritone 挖着矿）。 */
    private void stopEverythingAndTasks() {
        com.alonediamond.playercontrolpp.feature.automaterial.crafting.KnownMiningSurvey.clear();
        ContainerHighlightFeature.getInstance().clearGatherTarget();
        stopEverything();
        rawMaterialTask.cancel(ctx.client);
        shulkerAccess.cancel(ctx.client);
        clearMixedBoxReturn();
    }

    private void tickTask() {
        if (!active || ctx.client.player == null || ctx.client.level == null || ctx.client.player.isDeadOrDying()) {
            if (active) {
                setState(State.STOPPED);
            }
            return;
        }

        // ---- 潜影盒存取子系统（存盒 / 开盒取物 / 合成前取料）----
        if (shulkerAccess.isActive()) {
            handleShulkerAccessResult(shulkerAccess.tick(ctx));
            return;
        }

        if (tickRequestedStorage()) return;
        if (tickStorageSynchronization()) return;

        // ---- 原材料追溯子系统：只在它需要拿主意时接管，派出去采集时让路给下面的状态 ----
        if (rawMaterialTask.isActive() && rawMaterialTask.ownsFlow()) {
            rawMaterialTask.tick(ctx, this);
            return;
        }

        if (tickMixedBoxReturn()) return;

        tickContainerCooldown();

        switch (state) {
            case IDLE:
                if (active) {
                    setState(State.ANALYZING);
                }
                break;

            case ANALYZING:
                analyze();
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
                if (ctx.transferCooldown > 0) break;
                verifyTransfer();
                break;

            case NEXT_ITEM:
                nextItem();
                break;

            case OPENING_CONTAINER:
                // 由上面的 transferCooldown 机制驱动。
                break;

            case FAILED:
                // 寻路卡住 / 没开始，只是"这一项这次拿不到"：先给追溯任务一次机会，再决定跳过。
                onCurrentTargetUnavailable();
                break;

            case STOPPED:
            default:
                break;
        }
    }

    /** 追溯失败请求的可选整理优先于普通采集，未启动时同 tick 继续后续流程。 */
    private boolean tickRequestedStorage() {
        if (!pendingTraceStorage) return false;
        pendingTraceStorage = false;
        clearMixedBoxReturn();
        ctx.mixedBoxReturnClosing = false;
        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);
        voluntaryStorage = true;
        if (shulkerAccess.startStorage(ctx, true)) {
            return true;
        }
        voluntaryStorage = false;
        return false;
    }

    /** 盒子已关闭后才核对库存，避免把客户端预测当成最终结果。 */
    private boolean tickStorageSynchronization() {
        if (!pendingStorageDone) return false;
        if (storageSyncTicks < STORAGE_SYNC_TICKS) {
            storageSyncTicks++;
            return true;
        }
        pendingStorageDone = false;
        storageSyncTicks = 0;
        if (requiredStorageStartCount >= 0) {
            int before = requiredStorageStartCount;
            requiredStorageStartCount = -1;
            if (shulkerAccess.countStorableMaterials(ctx) >= before) {
                setState(State.STOPPED, "playercontrolpp.message.baritone.inventory_full");
                return true;
            }
        }

        // 开盒取物之后：盒子是从容器里拿出来的就先还回去。
        if (ctx.mixedBoxReturnSlot >= 0) {
            MixedBoxReturn.start(ctx, this, containerOpener);
            return true;
        }

        resumeAfterStorage();
        return true;
    }

    private boolean tickMixedBoxReturn() {
        if (!ctx.mixedBoxReturnClosing) return false;
        if (ctx.transferCooldown > 0) {
            ctx.transferCooldown--;
            return true;
        }
        MixedBoxReturn.verify(ctx, this);
        return true;
    }

    /** 冷却结束可在本 tick 转入取物；调用后仍继续执行主状态。 */
    private void tickContainerCooldown() {
        if (ctx.transferCooldown > 0) {
            ctx.transferCooldown--;
            if (ctx.containerJustOpened && ctx.transferCooldown <= 0) {
                ctx.containerJustOpened = false;
                containerOpener.checkOpenResult(ctx, this, pathingController);
            }
        }
    }

    /**
     * 启动前的可选整理，只执行一次，失败时保留材料并继续普通备货。
     */
    private boolean startInitialStorage() {
        if (initialStorageDone) return false;
        initialStorageDone = true;
        if (!ShulkerBoxAccess.isEnabled() || !shulkerAccess.hasStorableMaterials(ctx)) return false;
        voluntaryStorage = true;
        return shulkerAccess.startStorage(ctx, true);
    }

    /** 满包时必须成功释放材料占用；同步后没有实际进展就停止，避免反复开盒。 */
    public void onInventoryFull() {
        // 存盒只挪缺失清单上的材料，手上一点都没有时它腾不出任何空间；
        // 它照样会报 DONE，而依然满着的背包会无限重新触发它。
        if (ShulkerBoxAccess.isEnabled() && shulkerAccess.hasStorableMaterials(ctx)) {
            pathingController.cancelPathing();
            containerOpener.closeAnyContainer(ctx.client);
            clearMixedBoxReturn();
            ctx.mixedBoxReturnClosing = false;
            requiredStorageStartCount = shulkerAccess.countStorableMaterials(ctx);
            if (shulkerAccess.startStorage(ctx)) {
                return;
            }
        }
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.inventory_full");
        setState(State.STOPPED);
    }

    /**
     * 追溯失败后请求把这一轮收集到的原材料存进潜影盒。
     *
     * <p>只在开了「材料自动存入潜影盒」且背包里确实有可存的东西时才生效；
     * 存不进去（没开关、没盒子、没东西可存）就当没这回事，材料照旧留在背包里。
     */
    public void requestTraceStorage(boolean resumeNextItem) {
        if (!ShulkerBoxAccess.isEnabled()) return;
        if (!shulkerAccess.hasStorableMaterials(ctx)) return;
        this.pendingTraceStorage = true;
        // 追溯以"放弃这个物品"收场时（索引早就推进过了），存完直接走下一个物品：
        // 这时的 ctx.targetItem() 还是刚失败的那个，拿它重判会把这个物品再搜一遍，
        // 甚至把索引多推一格。
        this.resumeNextItemAfterStorage = resumeNextItem;
    }

    /** 顶层物品的"这个不要了"：跳过它，处理清单里的下一个。 */
    public void skipCurrentItem() {
        currentItemIndex++;
        setState(State.NEXT_ITEM);
    }

    // ---- 当前采集目标的统一出口 ----

    /**
     * 当前采集目标已经凑齐。
     *
     * <p>顶层物品直接推进到下一个；如果正处在原材料追溯途中，这是"某个子材料到手了"的信号，
     * 交给追溯任务继续推进它的计划。
     */
    public void onCurrentTargetSatisfied() {
        if (rawMaterialTask.isActive()) {
            rawMaterialTask.onSubItemDone(ctx, this);
            return;
        }
        currentItemIndex++;
        setState(State.NEXT_ITEM);
    }

    /**
     * 当前采集目标在箱子追踪缓存里彻底找不到了。
     *
     * <p>同样分两层：追溯途中的子材料交给追溯任务决定"去挖还是继续往下分解"；
     * 顶层物品则先按配置尝试追溯原材料，实在不行才跳过。
     *
     * <p>搜索针对当前物品；打开容器后仍会顺带收集其他缺料。排除集合随当前物品切换而清空。
     */
    public void onCurrentTargetUnavailable() {
        if (rawMaterialTask.isActive()) {
            rawMaterialTask.onSubItemUnavailable(ctx, this);
            return;
        }
        if (tryStartRawMaterialTask()) {
            return;
        }
        skipCurrentItem();
    }

    // ---- 原材料追溯 ----

    /**
     * 顶层物品搜不到时，按配置尝试"追溯原材料并合成"。
     *
     * @return true = 已经进入追溯流程
     */
    private boolean tryStartRawMaterialTask() {
        RawMaterialMode mode =
                (RawMaterialMode) Configs.BaritoneSettings.RAW_MATERIAL_CRAFT_MODE.getOptionListValue();
        if (mode == RawMaterialMode.DISABLED && !(manualRun
                && manualPreparationMode == ManualPreparationMode.RAW_THEN_CRAFT && !preparingRaw)) return false;
        if (ctx.targetItem() == null) return false;
        // 追溯过一次还是凑不齐的物品不再重试，否则"搜不到 → 追溯 → 仍不够 → 再搜"会打转。
        if (ctx.tracedItems.contains(ctx.targetItem())) return false;

        int gap = ctx.targetTotal() - ctx.countHeld(ctx.targetItem());
        if (gap <= 0) return false;

        if (!checkRawMaterialPrerequisites()) return false;

        if (manualRun && manualPreparationMode == ManualPreparationMode.RAW_ONLY) {
            // 只备临时表的叶子，不重新从叶子展开，避免越过玩家指定的白名单或追溯深度。
            if (mode != RawMaterialMode.WORLD_SEARCH) return false;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ctx.targetItem()).toString();
            return rawMaterialTask.start(ctx, this, ctx.targetItem(), gap,
                    new LitematListIntegration.RawNode(id, gap, "base", java.util.List.of()));
        }
        return rawMaterialTask.start(ctx, this, ctx.targetItem(), gap,
                rawPreparation == null ? null : rawPreparation.rootFor(ctx.targetItem()));
    }

    /**
     * 追溯的两个前提：备货数据来源是「跟随LitematList清单」，且那边确实上传了清单。
     *
     * <p>不满足时只提示一次，然后按普通备货继续（作者确认：优化使用体验，不打断已有流程）。
     */
    private boolean checkRawMaterialPrerequisites() {
        if (rawMaterialPrereqChecked) return rawMaterialPrereqOk;
        rawMaterialPrereqChecked = true;

        if (!LitematListIntegration.getInstance().isLoaded()) return false;
        if (manualRun) { rawMaterialPrereqOk = true; return true; }
        if (Configs.BaritoneSettings.MATERIAL_LIST_SOURCE.getOptionListValue() != MaterialSource.LITEMATLIST) {
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.raw_material_needs_source");
            rawMaterialPrereqOk = false;
            return false;
        }
        if (!LitematListIntegration.getInstance().isMaterialListAvailable()) {
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.raw_material_no_list");
            rawMaterialPrereqOk = false;
            return false;
        }
        rawMaterialPrereqOk = true;
        return true;
    }

    /** 追溯任务跑完了一整份计划（不论顶层物品最终有没有凑齐）。 */
    public void onRawMaterialTaskFinished() {
        continueAfterExtraction();
    }

    // ---- 开盒取物之后的收尾 ----

    /** 开盒取物（以及把盒子还回去）之后重新核对当前物品，决定继续搜索还是换下一个。 */
    public void continueAfterExtraction() {
        if (ctx.storageAfterExtraction) {
            ctx.storageAfterExtraction = false;
            onInventoryFull();
            return;
        }
        if (ctx.targetItem() == null) {
            setState(State.NEXT_ITEM);
            return;
        }
        if (isCurrentItemSatisfied()) {
            onCurrentTargetSatisfied();
        } else {
            setState(State.SEARCHING);
        }
    }

    /** 清掉"待归还的杂盒"，归还成功、放弃归还、停止备货时都要清。 */
    public void clearMixedBoxReturn() {
        ctx.mixedBoxReturnSlot = -1;
        ctx.mixedBoxReturnTarget = null;
        ctx.mixedBoxIdentity = ItemStack.EMPTY;
        ctx.mixedBoxOriginSlot = -1;
    }

    /** 启动整理、满包整理及追溯失败整理统一从这里恢复，避免重复推进材料索引。 */
    private void resumeAfterStorage() {
        boolean nextItem = resumeNextItemAfterStorage;
        resumeNextItemAfterStorage = false;
        voluntaryStorage = false;
        if (nextItem) setState(State.NEXT_ITEM);
        else continueAfterExtraction();
    }

    /** 潜影盒存取子状态机的结果处理。 */
    private void handleShulkerAccessResult(ShulkerBoxAccess.StorageResult result) {
        switch (result) {
            case DONE -> {
                // "合成前把材料从盒里取出来"这一趟是追溯任务要的，不走走盒后的同步核对，
                // 直接把控制权还给它；其余情况才是存盒/杂盒取物的收尾。
                if (rawMaterialTask.isWaitingForUnpack()) {
                    rawMaterialTask.onUnpackFinished(ctx, this);
                    return;
                }
                pendingStorageDone = true;
                storageSyncTicks = 0;
            }
            case ABORTED -> {
                // "顺手存一下"没找到盒子：材料留着，继续备货。
                if (voluntaryStorage) {
                    resumeAfterStorage();
                    return;
                }
                // 开盒取物做不成（没地方放盒子 / 打不开 / 盒内没有要的东西）。
                clearMixedBoxReturn();
                if (rawMaterialTask.isWaitingForUnpack()) {
                    rawMaterialTask.onUnpackFinished(ctx, this);
                    return;
                }
                // 这个容器对本物品已经被证明没用，别再被搜索选中（否则会开箱-失败-再开箱）。
                // 然后回到搜索：缓存里还可能有别的杂盒；一个都不剩时 search() 自然会走
                // onCurrentTargetUnavailable()。
                if (ctx.currentContainerTarget != null) {
                    ctx.exhaustedPositions.add(ctx.currentContainerTarget);
                }
                setState(State.SEARCHING);
            }
            case INVENTORY_FULL -> onInventoryFull();
            case FAILED -> {
                shulkerAccess.cancel(ctx.client);
                setState(State.STOPPED);
            }
            case ACTIVE -> { }
        }
    }

}
