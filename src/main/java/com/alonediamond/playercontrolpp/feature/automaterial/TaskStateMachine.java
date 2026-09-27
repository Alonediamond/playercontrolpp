package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.MaterialSource;
import com.alonediamond.playercontrolpp.config.RawMaterialMode;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

/**
 * 驱动自动备货的状态机：把每个状态派发给拥有它的模块，并在它们之上跑两个子状态机——
 * {@code ShulkerBoxAccess}（存盒 / 开盒取物）与 {@code RawMaterialTask}（追溯原材料并合成）。
 *
 * <p>三个"放弃当前目标"的出口集中在这里：{@link #skipCurrentItem()} 是顶层的"这个物品不要了"，
 * {@link #onCurrentTargetSatisfied()} / {@link #onCurrentTargetUnavailable()} 则会先问一句
 * 原材料追溯任务——追踪途中的"子材料到手了/找不到了"是它的推进信号，不是顶层物品的成败。
 */
public class TaskStateMachine {

    /**
     * 存盒结束后再等几 tick，让服务端的物品栏更新先到，再去数手上真正有多少。
     */
    private static final int STORAGE_SYNC_TICKS = 15;

    private final GatherContext ctx;
    private final MaterialAnalyzer materialAnalyzer;
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

    /** 存盒后的「同步并核对」还没做完时为 true。 */
    private boolean pendingStorageDone;
    private int storageSyncTicks;
    /** FAILED 转换没给原因时的兜底文案。 */
    private static final String DEFAULT_FAILURE_KEY = "playercontrolpp.message.baritone.pathing_stuck";

    public TaskStateMachine(GatherContext ctx,
                            MaterialAnalyzer materialAnalyzer,
                            ContainerSearcher containerSearcher,
                            BaritonePathingController pathingController,
                            ContainerOpener containerOpener,
                            ItemTransferExecutor transferExecutor,
                            ShulkerBoxAccess shulkerAccess,
                            RawMaterialTask rawMaterialTask) {
        this.ctx = ctx;
        this.materialAnalyzer = materialAnalyzer;
        this.containerSearcher = containerSearcher;
        this.pathingController = pathingController;
        this.containerOpener = containerOpener;
        this.transferExecutor = transferExecutor;
        this.shulkerAccess = shulkerAccess;
        this.rawMaterialTask = rawMaterialTask;
    }

    public void setState(State newState) {
        setState(newState, null);
    }

    /** 每次开始新一轮备货时调用：清掉只在单轮内有效的判断与中间状态。 */
    public void resetRunState() {
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
                ctx.active = false;
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
        stopEverything();
        rawMaterialTask.cancel(ctx.client);
    }

    public void tick() {
        if (!ctx.active || ctx.client.player == null || ctx.client.player.isDeadOrDying()) {
            if (ctx.active) {
                setState(State.STOPPED);
            }
            return;
        }

        // ---- 潜影盒存取子系统（存盒 / 开盒取物 / 合成前取料）----
        if (shulkerAccess.isActive()) {
            handleShulkerAccessResult(shulkerAccess.tick(ctx));
            return;
        }

        // ---- 追溯失败后的"把散落的原材料收进潜影盒" ----
        if (pendingTraceStorage) {
            pendingTraceStorage = false;
            clearMixedBoxReturn();
            ctx.mixedBoxReturnClosing = false;
            pathingController.cancelPathing();
            containerOpener.closeAnyContainer(ctx.client);
            voluntaryStorage = true;
            if (shulkerAccess.startStorage(ctx, true)) {
                return;
            }
            voluntaryStorage = false;
        }

        // ---- 存盒后的同步与核对（独立于 isActive() 运行）----
        if (pendingStorageDone) {
            if (storageSyncTicks < STORAGE_SYNC_TICKS) {
                storageSyncTicks++;
                return;
            }
            pendingStorageDone = false;
            storageSyncTicks = 0;

            // 开盒取物之后：盒子是从容器里拿出来的就先还回去。
            if (ctx.mixedBoxReturnSlot >= 0) {
                startMixedBoxReturn();
                return;
            }

            // 追溯失败触发的存盒：当前目标已经作废（索引早就推进过了），直接走下一个物品。
            if (resumeNextItemAfterStorage) {
                resumeNextItemAfterStorage = false;
                voluntaryStorage = false;
                setState(State.NEXT_ITEM);
                return;
            }
            voluntaryStorage = false;

            if (ctx.currentTargetItem == null) {
                // 存盒发生在还没选定任何物品之前（从 ANALYZING 触发的）；
                // 此时去搜索会拿 null 物品去问箱子追踪。改为重新分析。
                setState(State.ANALYZING);
            } else if (transferExecutor.isCurrentItemSatisfied(ctx)) {
                onCurrentTargetSatisfied();
            } else {
                setState(State.SEARCHING);
            }
            return;
        }

        // ---- 原材料追溯子系统：只在它需要拿主意时接管，派出去采集时让路给下面的状态 ----
        if (rawMaterialTask.isActive() && rawMaterialTask.ownsFlow()) {
            rawMaterialTask.tick(ctx, this);
            return;
        }

        // ---- 杂盒归还：点完"塞回容器"之后的收尾 ----
        if (ctx.mixedBoxReturnClosing) {
            if (ctx.transferCooldown > 0) {
                ctx.transferCooldown--;
                return;
            }
            ctx.mixedBoxReturnClosing = false;
            int boxSlot = ctx.mixedBoxReturnSlot;
            if (boxSlot >= 0) {
                ctx.extractionBoxSlots.remove(boxSlot);
            }
            containerOpener.closeAnyContainer(ctx.client);
            clearMixedBoxReturn();
            continueAfterExtraction();
            return;
        }

        // ---- 开容器冷却 ----
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

    /**
     * 检测到背包已满时调用。用户开了自动存盒就交给存盒流程，否则以「背包已满」提示停止。
     */
    public void onInventoryFull() {
        // 刚刚才取走一整盒，这时再往盒子里存等于立刻撤销自己。
        if (ctx.justTookShulkerBox) {
            ctx.justTookShulkerBox = false;
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.inventory_full");
            setState(State.STOPPED);
            return;
        }

        // 存盒只挪缺失清单上的材料，手上一点都没有时它腾不出任何空间；
        // 它照样会报 DONE，而依然满着的背包会无限重新触发它。
        if (ShulkerBoxAccess.isEnabled() && shulkerAccess.hasStorableMaterials(ctx)) {
            pathingController.cancelPathing();
            containerOpener.closeAnyContainer(ctx.client);
            clearMixedBoxReturn();
            ctx.mixedBoxReturnClosing = false;
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
        // 这时的 ctx.currentTargetItem 还是刚失败的那个，拿它重判会把这个物品再搜一遍，
        // 甚至把索引多推一格。
        this.resumeNextItemAfterStorage = resumeNextItem;
    }

    /** 顶层物品的"这个不要了"：跳过它，处理清单里的下一个。 */
    public void skipCurrentItem() {
        ctx.currentItemIndex++;
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
        ctx.currentItemIndex++;
        setState(State.NEXT_ITEM);
    }

    /**
     * 当前采集目标在箱子追踪缓存里彻底找不到了。
     *
     * <p>同样分两层：追溯途中的子材料交给追溯任务决定"去挖还是继续往下分解"；
     * 顶层物品则先按配置尝试追溯原材料，实在不行才跳过。
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
        if (mode == RawMaterialMode.DISABLED) return false;
        if (ctx.currentTargetItem == null) return false;
        // 追溯过一次还是凑不齐的物品不再重试，否则"搜不到 → 追溯 → 仍不够 → 再搜"会打转。
        if (ctx.tracedItems.contains(ctx.currentTargetItem)) return false;

        int gap = ctx.targetNeededTotal - ctx.currentlyGathered;
        if (gap <= 0) return false;
        if (!checkRawMaterialPrerequisites()) return false;

        return rawMaterialTask.start(ctx, this, ctx.currentTargetItem, gap);
    }

    /**
     * 追溯的两个前提：备货数据来源是「跟随LitematList清单」，且那边确实上传了清单。
     *
     * <p>不满足时只提示一次，然后按普通备货继续（作者确认：优化使用体验，不打断已有流程）。
     */
    private boolean checkRawMaterialPrerequisites() {
        if (rawMaterialPrereqChecked) return rawMaterialPrereqOk;
        rawMaterialPrereqChecked = true;

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
        if (ctx.currentTargetItem == null) {
            setState(State.ANALYZING);
            return;
        }
        if (transferExecutor.isCurrentItemSatisfied(ctx)) {
            onCurrentTargetSatisfied();
        } else {
            setState(State.SEARCHING);
        }
    }

    /**
     * 把从容器里取出来的杂盒还回原容器。
     *
     * <p>复用现成的开容器机制：够得着就直接开，界面出现后由
     * {@code ContainerOpener.checkOpenResult} 把盒子 quickMove 回容器；够不着就提示一声，
     * 盒子留在背包里继续用（作者确认：不需要塞回去，材料已经拿到手了）。
     */
    private void startMixedBoxReturn() {
        Minecraft mc = ctx.client;
        int boxSlot = ctx.mixedBoxReturnSlot;
        BlockPos target = ctx.mixedBoxReturnTarget;

        if (mc.player == null || target == null || boxSlot < 0 || boxSlot >= Inventory.INVENTORY_SIZE
                || !ItemUtil.isShulkerBox(mc.player.getInventory().getItem(boxSlot))) {
            clearMixedBoxReturn();
            continueAfterExtraction();
            return;
        }

        if (mc.player.blockPosition().distSqr(target) > PlayerUtil.blockReachSq(mc.player)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_return_failed");
            ctx.extractionBoxSlots.remove(boxSlot);
            clearMixedBoxReturn();
            continueAfterExtraction();
            return;
        }

        setState(State.OPENING_CONTAINER);
        containerOpener.openContainerAt(target, ctx);
    }

    /** 清掉"待归还的杂盒"，归还成功、放弃归还、停止备货时都要清。 */
    public void clearMixedBoxReturn() {
        ctx.mixedBoxReturnSlot = -1;
        ctx.mixedBoxReturnTarget = null;
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
                    voluntaryStorage = false;
                    resumeNextItemAfterStorage = false;
                    continueAfterExtraction();
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
                if (voluntaryStorage) {
                    voluntaryStorage = false;
                    resumeNextItemAfterStorage = false;
                    continueAfterExtraction();
                    return;
                }
                setState(State.STOPPED);
            }
            case ACTIVE -> { }
        }
    }
}
