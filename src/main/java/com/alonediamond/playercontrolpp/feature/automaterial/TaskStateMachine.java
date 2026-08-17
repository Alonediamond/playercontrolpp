package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.util.MessageUtil;

/**
 * 驱动自动备货的状态机：把每个状态派发给拥有它的模块，背包满时运行 ShulkerBoxStorage 子状态机。
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
    private final ShulkerBoxStorage shulkerStorage;

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

    /** 取消寻路、关掉所有容器、松开全部模拟按键。 */
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

        // ---- 存盒后的同步与核对（独立于 isActive() 运行）----
        if (pendingStorageDone) {
            if (storageSyncTicks < STORAGE_SYNC_TICKS) {
                storageSyncTicks++;
                return;
            }
            pendingStorageDone = false;
            storageSyncTicks = 0;

            if (ctx.currentTargetItem == null) {
                // 存盒发生在还没选定任何物品之前（从 ANALYZING 触发的）；
                // 此时去搜索会拿 null 物品去问箱子追踪。改为重新分析。
                setState(State.ANALYZING);
            } else if (transferExecutor.isCurrentItemSatisfied(ctx)) {
                ctx.currentItemIndex++;
                setState(State.NEXT_ITEM);
            } else {
                setState(State.SEARCHING);
            }
            return;
        }

        // ---- 潜影盒存储子系统 ----
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
                skipCurrentItem();
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
        if (ShulkerBoxStorage.isEnabled() && shulkerStorage.hasStorableMaterials(ctx)) {
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
