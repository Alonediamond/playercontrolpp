package com.alonediamond.playercontrolpp.feature.automaterial.craft;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.RawMaterialMode;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.MaterialMemory;
import com.alonediamond.playercontrolpp.feature.automaterial.TaskStateMachine;
import com.alonediamond.playercontrolpp.feature.automaterial.storage.ShulkerBoxAccess;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 「材料备货追溯原材料并合成」的执行器。
 *
 * <p>顶层物品在箱子追踪里搜不到时启动：拿 LitematList 的配方树把"还缺的这些"向下分解，
 * 再后序地把每一层做出来——先保证子材料到手（能从缓存拿就从缓存拿、能挖就挖、能分解就再往下分解），
 * 子材料齐了才用工作台/切石机把这一层合成出来。
 *
 * <h3>子材料的采集复用主状态机</h3>
 * 每一层的子材料并不自己写一套"搜索→寻路→开箱→取物"，而是把
 * {@code ctx.currentTargetItem / targetNeededTotal} 临时指向它，然后交给主状态机的
 * {@code SEARCHING…VERIFYING} 跑；采完（或确认拿不到）由主状态机回调
 * {@link #onSubItemDone} / {@link #onSubItemUnavailable} 回到计划上来。
 *
 * <h3>预留额度</h3>
 * 清单上的每个物品都有一个"必须保留"的目标量。合成只能消耗
 * {@code 持有量 − 目标量} 的那部分富余；富余不够就先把差额当新的采集任务补上。
 * 否则"用箱子合成漏斗"会把刚为清单备好的箱子吃掉。
 */
public class RawMaterialTask {

    /** LitematList 原材料界面的默认层数：观察不到玩家设置时用它。 */
    private static final int DEFAULT_DEPTH = 5;

    /**
     * 同一层合成前最多开几次盒子取料。
     *
     * <p>子材料可能各自被自动存盒（背包满时），原则上每个都值得取一次；
     * 但也要有上限，免得在"取不出来"的盒子上反复开关。
     */
    private static final int MAX_UNPACKS_PER_FRAME = 4;

    /**
     * 计划里的一帧：把 {@code item} 一直做到 {@link #targetTotal} 个。
     *
     * <p>一帧不是一口气做完的：{@link #batch} 是这一轮要做的数量（{@link #batchSize}），
     * 这一轮的材料凑齐、合成完，再开始下一轮。
     */
    private static final class Frame {
        final Item item;
        final RawNode node;
        /** 这一帧最终要持有到的绝对数量（含清单给它的预留量）。 */
        final int targetTotal;
        /** 顶层物品：它的目标是"补上清单缺口"，而不是"额外做出这个数量供上层消耗"。 */
        final boolean root;
        int nextChild;
        /** 本轮批次要做的数量；0 = 这一轮还没开始。 */
        int batch;
        /** 本轮开过几次盒子取料。每个子材料最多各一次，且总数有上限，避免来回开关盒子。 */
        int unpackAttempts;
        /**
         * 本轮是否已经为"树上没有、但配方要的材料"额外派过一次采集。
         *
         * <p>只派一次：那个物品如果本来就躺在打不开的盒子里，采集会立刻判定"已持有"，
         * 再派一次就是死循环。
         */
        boolean extraGatherTried;

        Frame(Item item, RawNode node, int targetTotal, boolean root) {
            this.item = item;
            this.node = node;
            this.targetTotal = targetTotal;
            this.root = root;
        }
    }

    /**
     * 一轮（一批）最多做多少个：目标物品的一整组。
     *
     * <p>作者实测：一次把"要 3456 个活塞"的材料全取出来，背包根本放不下。改为一组一组地做——
     * 凑够一组的材料 → 合成一组 → 再凑下一组。这样批内的材料量有界，工作台摆一次料就能做完整批，
     * 也顺带避开了合成器"连摆 6 次就放弃"的上限。
     */
    private static int batchSize(Item item) {
        int stack = item == null ? 64 : item.getDefaultMaxStackSize();
        return Math.max(1, stack);
    }

    private enum Stage {
        IDLE,
        /** 正在决定"下一个要保证的子材料"或发起合成。 */
        GATHER,
        /** 已经把某个子材料交给主状态机去采集，等它的回调。 */
        DISPATCHED,
        /** 正在用 Baritone 挖。 */
        MINING,
        /** 等"把材料从背包内的潜影盒里取出来"这一次做完。 */
        WAIT_UNPACK,
        /** 正在合成。 */
        CRAFT
    }

    private final LitematListIntegration litematlist = LitematListIntegration.getInstance();
    private final BaritoneIntegration baritone = BaritoneIntegration.getInstance();
    private final ShulkerBoxAccess shulkerAccess;
    private final CraftingController crafting = new CraftingController();

    public RawMaterialTask(ShulkerBoxAccess shulkerAccess) {
        this.shulkerAccess = shulkerAccess;
    }

    private Stage stage = Stage.IDLE;
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** 本次追溯的顶层物品；失败时记进 ctx.tracedItems，避免反复重试。 */
    private Item rootItem;

    // 已经派出去的那一项
    private Item dispatchedItem;
    private RawNode dispatchedNode;
    private int dispatchedNeed;
    private int dispatchedTarget;
    private boolean dispatchedIsRoot;
    /** 这次派出去的是"配方要、树上没有"的额外材料：回来时不要推进树上的子材料下标。 */
    private boolean dispatchedExtra;

    /** 正在从盒里取出的那个物品；只用于让日志/提示说得清楚。 */
    private Item unpackItem;

    /** 本次运行是否已经提示过"没有可用工作站"，同一条提示不刷屏。 */
    private boolean craftingWarned;

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    /**
     * @return 现在要不要由本任务接管 tick。派子材料给主状态机之后（{@link Stage#DISPATCHED}）
     *         必须让路，否则主状态机的搜索/开箱状态永远不会被执行。
     */
    public boolean ownsFlow() {
        return isActive() && this.stage != Stage.DISPATCHED && this.stage != Stage.WAIT_UNPACK;
    }

    /** @return 正交给潜影盒存取子状态机"把材料取出来"，主状态机据此把 DONE 交回给本任务。 */
    public boolean isWaitingForUnpack() {
        return this.stage == Stage.WAIT_UNPACK;
    }

    /**
     * 为一个顶层物品建立计划并开始执行。
     *
     * @param gap 这个物品还差多少个（= 需求总量 − 当前持有）
     * @return false = 建不出计划（没有配方 / 是白名单材料又挖不到），调用方按原逻辑跳过该物品
     */
    public boolean start(GatherContext ctx, TaskStateMachine tsm, Item item, int gap) {
        if (ctx.client == null || ctx.client.player == null) return false;
        reset();

        int need = Math.max(1, gap);
        RawNode root = this.litematlist.getRawMaterialTree(item, need, resolveDepth());
        if (root == null) {
            // 建不出树（LitematList 里没有这个物品的配方）时说一声，别让玩家只看到"跳过了"。
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.raw_material_no_recipe", name(item));
            return false;
        }

        this.rootItem = item;
        remember(ctx, item);

        if (root.children().isEmpty()) {
            // 溯源白名单里的材料本身没有配方（铁锭、木棍……）：只能去世界里挖。
            if (!canMineHere() || MiningSources.blocksFor(item).isEmpty()) return false;
            startMining(ctx, tsm, item, need, null, true);
            return true;
        }

        this.frames.push(new Frame(item, root, absoluteTarget(ctx, item, need, true), true));
        this.stage = Stage.GATHER;
        MessageUtil.sendActionBar(ctx.client,
                "playercontrolpp.message.baritone.raw_material_start", name(item));
        return true;
    }

    public void tick(GatherContext ctx, TaskStateMachine tsm) {
        if (ctx.client == null || ctx.client.player == null) {
            reset();
            return;
        }
        switch (this.stage) {
            case GATHER -> gatherTick(ctx, tsm);
            case MINING -> miningTick(ctx, tsm);
            case CRAFT -> craftTick(ctx, tsm);
            default -> { }
        }
    }

    /** 停止备货 / 世界切换时调用：取消挖矿、关掉合成界面。 */
    public void cancel(Minecraft mc) {
        if (this.stage == Stage.MINING) {
            this.baritone.cancelMining();
        }
        this.crafting.cancel(mc);
        reset();
    }

    // ---- 主状态机回调 ----

    /** 派出去的那一项已经采集够了。 */
    public void onSubItemDone(GatherContext ctx, TaskStateMachine tsm) {
        // 只认"我们确实在等"的回调：合成中、取料中冒出来的 satisfied 事件不能拿来推进计划，
        // 否则会把正开着的合成界面丢在那里不管。
        if (this.stage != Stage.DISPATCHED && this.stage != Stage.MINING) return;
        if (this.dispatchedItem == null) return;
        boolean wasRoot = this.dispatchedIsRoot;
        boolean wasExtra = this.dispatchedExtra;
        this.dispatchedItem = null;
        this.dispatchedNode = null;
        this.dispatchedExtra = false;

        if (wasRoot || this.frames.isEmpty()) {
            finish(ctx, tsm);
            return;
        }
        if (wasExtra) {
            // 额外收集回来的材料：树上的子材料下标没动过，直接回到这一帧的合成决策。
            this.stage = Stage.GATHER;
            return;
        }
        Frame frame = this.frames.peek();
        if (frame != null) frame.nextChild++;
        this.stage = Stage.GATHER;
    }

    /**
     * 「把材料从背包内的潜影盒里取出来」这一趟做完了（成功、失败或被中止都会回调）。
     *
     * <p>回到 GATHER 重新走一遍合成决策：盒子里取出来的材料这下是散装的了，直接进合成；
     * 还有别的子材料躺在盒里就再取一次（{@code unpackAttempts} 记着次数，有上限），
     * 实在取不出来就按"没有可用工作站"收场，不会来回开关盒子。
     */
    public void onUnpackFinished(GatherContext ctx, TaskStateMachine tsm) {
        this.unpackItem = null;
        if (this.frames.isEmpty()) {
            if (this.dispatchedItem == null) finish(ctx, tsm);
            return;
        }
        this.stage = Stage.GATHER;
    }

    /** 派出去的那一项在缓存里没有。 */
    public void onSubItemUnavailable(GatherContext ctx, TaskStateMachine tsm) {
        if (this.stage != Stage.DISPATCHED) return;
        Item item = this.dispatchedItem;
        if (item == null) {
            failChain(ctx, tsm, this.rootItem);
            return;
        }
        afterGatherFailed(ctx, tsm, item, true);
    }

    // ---- 计划推进 ----

    private void gatherTick(GatherContext ctx, TaskStateMachine tsm) {
        Frame frame = this.frames.peek();
        if (frame == null) {
            finish(ctx, tsm);
            return;
        }

        // 刚进这一帧、或刚合成完一批：按"还差多少"重新算这一轮做多少个。
        if (frame.batch <= 0 && !beginBatch(ctx, frame)) {
            popFrame(ctx, tsm);
            return;
        }

        List<RawNode> children = frame.node.children();
        while (frame.nextChild < children.size()) {
            RawNode childNode = children.get(frame.nextChild);
            Item childItem = resolveItem(childNode.itemId());
            if (childItem == null) {
                frame.nextChild++;
                continue;
            }

            remember(ctx, childItem);

            int childNeed = scaleCount(childNode.count(), frame.batch, frame.node.count());
            // 可动用富余 = 持有量 − 这个物品自己要留给清单的量；合成只能吃富余。
            int surplus = ItemUtil.countEverywhere(ctx.client.player, childItem) - ctx.targetFor(childItem);
            if (surplus >= childNeed) {
                frame.nextChild++;
                continue;
            }

            dispatchGather(ctx, tsm, childItem, childNode, childNeed, false);
            return;
        }

        startCraft(ctx, tsm, frame);
    }

    /**
     * 开始这一帧的下一轮批次。
     *
     * @return false = 这一帧已经做够了，调用方结束它
     */
    private static boolean beginBatch(GatherContext ctx, Frame frame) {
        int remaining = frame.targetTotal - ItemUtil.countEverywhere(ctx.client.player, frame.item);
        if (remaining <= 0) return false;
        frame.batch = Math.min(remaining, batchSize(frame.item));
        frame.nextChild = 0;
        frame.unpackAttempts = 0;
        frame.extraGatherTried = false;
        return true;
    }

    private void dispatchGather(GatherContext ctx, TaskStateMachine tsm, Item item,
                                RawNode node, int need, boolean isRoot) {
        remember(ctx, item);
        this.dispatchedItem = item;
        this.dispatchedNode = node;
        this.dispatchedNeed = need;
        this.dispatchedIsRoot = isRoot;
        this.dispatchedTarget = absoluteTarget(ctx, item, need, isRoot);
        this.stage = Stage.DISPATCHED;

        ctx.currentTargetItem = item;
        ctx.targetNeededTotal = this.dispatchedTarget;
        ctx.currentlyGathered = ItemUtil.countEverywhere(ctx.client.player, item);
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.foundPositions.clear();
        ctx.exhaustedPositions.clear();
        ctx.wholeBoxPriority = false;
        ctx.mixedBoxMode = false;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;
        tsm.setState(State.SEARCHING);
    }

    private void startCraft(GatherContext ctx, TaskStateMachine tsm, Frame frame) {
        remember(ctx, frame.item);
        Item input = firstChildItem(frame.node);
        // 只做到"现在手上的 + 这一批"：合成器数到目标量就停手，不会多做。
        int target = ItemUtil.countEverywhere(ctx.client.player, frame.item) + frame.batch;

        if (!this.crafting.start(ctx.client, frame.item, target, frame.node.recipeType(), input)) {
            // 熔炼 / 锻造 / 没有工作站 / 没有可用配方：按约定只保留已经收集到的子材料，不做这一层合成。
            warnCraftUnavailable(ctx, frame);
            popFrame(ctx, tsm);
            return;
        }

        // 开工前的自检：材料在物品栏里真的摆得出来吗？
        // 早先直接开界面：材料还躺在潜影盒里、或散装量不足一次合成时，工作台照样被打开、
        // 摆料失败、整帧白跑——表现就是作者反馈的"没收集够就打开工作台合成，然后失败"。
        java.util.List<CraftingController.BlockedIngredient> blocked =
                this.crafting.blockedIngredients(ctx.client);
        if (!blocked.isEmpty()) {
            // 界面还没开，cancel 只是把配方计划复位（不会关掉玩家自己的物品栏）。
            this.crafting.cancel(ctx.client);
            if (tryUnpackBlocked(ctx, frame, blocked)) {
                this.stage = Stage.WAIT_UNPACK;
                return;
            }
            if (!frame.extraGatherTried && dispatchExtraGather(ctx, tsm, frame, blocked)) return;
            logBlocked(frame, blocked);
            warnIngredientsBlocked(ctx, frame);
            popFrame(ctx, tsm);
            return;
        }

        this.stage = Stage.CRAFT;
        MessageUtil.sendActionBar(ctx.client,
                "playercontrolpp.message.baritone.raw_material_crafting", name(frame.item));
    }

    /** 没有可用配方 / 没有工作站时的提示（每种原因只说一次）。 */
    private void warnCraftUnavailable(GatherContext ctx, Frame frame) {
        if (this.craftingWarned) return;
        this.craftingWarned = true;
        boolean supported = isSupportedRecipe(frame.node.recipeType());
        MessageUtil.sendActionBar(ctx.client, supported
                        ? "playercontrolpp.message.baritone.raw_material_no_workstation"
                        : "playercontrolpp.message.baritone.raw_material_no_recipe",
                name(frame.item));
    }

    /** 材料凑不出一次合成时的提示。 */
    private void warnIngredientsBlocked(GatherContext ctx, Frame frame) {
        if (this.craftingWarned) return;
        this.craftingWarned = true;
        MessageUtil.sendActionBar(ctx.client,
                "playercontrolpp.message.baritone.raw_material_ingredients_blocked", name(frame.item));
    }

    /** 把卡住的原因打进日志，便于实机排查"为什么没合成"。 */
    private void logBlocked(Frame frame, java.util.List<CraftingController.BlockedIngredient> blocked) {
        StringBuilder sb = new StringBuilder();
        for (CraftingController.BlockedIngredient ingredient : blocked) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name(ingredient.item())).append(" x").append(ingredient.perCraft())
                    .append(ingredient.boxed() ? "(盒内)" : "(无货)");
        }
        Playercontrolpp.LOGGER.info("[craft] blocked: item={} batch={} missing={}",
                name(frame.item), frame.batch, sb);
    }

    /**
     * 卡住的材料其实"盒里有、只是没取出来"时，开盒把它取成散装。
     *
     * @return true = 已经交给取盒子状态机，等它回调
     */
    private boolean tryUnpackBlocked(GatherContext ctx, Frame frame,
                                     java.util.List<CraftingController.BlockedIngredient> blocked) {
        if (ctx.client.player == null) return false;
        for (CraftingController.BlockedIngredient ingredient : blocked) {
            if (!ingredient.boxed()) continue;
            if (frame.unpackAttempts >= MAX_UNPACKS_PER_FRAME) return false;
            // 散装要凑到"清单给它的预留量 + 这一批合成要用的量"（和树上子材料的绝对目标同一口径）。
            int desiredLoose = ctx.targetFor(ingredient.item())
                    + ingredient.perCraft() * this.crafting.craftsNeeded(frame.batch);
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                ItemStack stack = ctx.client.player.getInventory().getItem(i);
                if (!ItemUtil.isShulkerBox(stack) || !ItemUtil.containsInside(stack, ingredient.item())) continue;
                // QuickShulker 开潜影盒要求数量恰好为 1（它注册时没设 ignoreSingleStackCheck），
                // 这种盒子开不了，别浪费一轮开关。
                if (ShulkerBoxAccess.isQuickShulkerModeEnabled()
                        && !QuickShulkerIntegration.isOpenableShulkerBox(stack)) {
                    continue;
                }
                if (this.shulkerAccess.startExtraction(ctx, i, null,
                        Map.of(ingredient.item(), desiredLoose))) {
                    frame.unpackAttempts++;
                    this.unpackItem = ingredient.item();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 配方要的材料树上根本没有（客户端选中的配方与 LitematList 选的那条不一致时会这样）：
     * 按"这一批要合成几次 × 每格用量"把它收集回来，而不是硬开工作台。
     *
     * @return true = 已经派出去采集
     */
    private boolean dispatchExtraGather(GatherContext ctx, TaskStateMachine tsm, Frame frame,
                                        java.util.List<CraftingController.BlockedIngredient> blocked) {
        frame.extraGatherTried = true;
        for (CraftingController.BlockedIngredient ingredient : blocked) {
            // 盒里有货的交给上面那一趟取料；已经持有（只是不在散装）的收集也解决不了。
            if (ingredient.boxed()) continue;
            if (ItemUtil.countEverywhere(ctx.client.player, ingredient.item()) > 0) continue;
            int need = Math.max(1, ingredient.perCraft() * this.crafting.craftsNeeded(frame.batch));
            this.dispatchedExtra = true;
            dispatchGather(ctx, tsm, ingredient.item(), null, need, false);
            return true;
        }
        return false;
    }

    private void craftTick(GatherContext ctx, TaskStateMachine tsm) {
        switch (this.crafting.tick(ctx.client)) {
            case ACTIVE -> { }
            case DONE -> {
                // 一批做完：这一帧还差就接着凑下一批的材料，够了才收工。
                Frame frame = this.frames.peek();
                if (frame != null
                        && ItemUtil.countEverywhere(ctx.client.player, frame.item) < frame.targetTotal) {
                    frame.batch = 0;
                    this.stage = Stage.GATHER;
                } else {
                    popFrame(ctx, tsm);
                }
            }
            case FAILED -> {
                Frame frame = this.frames.peek();
                MessageUtil.sendActionBar(ctx.client,
                        "playercontrolpp.message.baritone.raw_material_craft_failed",
                        name(frame != null ? frame.item : ctx.currentTargetItem));
                this.crafting.cancel(ctx.client);
                popFrame(ctx, tsm);
            }
            case INVENTORY_FULL -> {
                // 界面先关掉，再把背包满交给主状态机（存盒 / 停机提示）。
                // 刻意不记进 tracedItems：腾出空间之后这一项还可以再追溯一次。
                this.crafting.cancel(ctx.client);
                Item root = this.rootItem;
                reset();
                if (root != null) {
                    ctx.currentTargetItem = root;
                    ctx.targetNeededTotal = ctx.targetFor(root);
                    ctx.currentlyGathered = ItemUtil.countEverywhere(ctx.client.player, root);
                }
                tsm.onInventoryFull();
            }
        }
    }

    private void popFrame(GatherContext ctx, TaskStateMachine tsm) {
        this.frames.poll();
        if (this.frames.isEmpty()) {
            finish(ctx, tsm);
            return;
        }
        Frame parent = this.frames.peek();
        if (parent != null) parent.nextChild++;
        this.stage = Stage.GATHER;
    }

    private void finish(GatherContext ctx, TaskStateMachine tsm) {
        Item root = this.rootItem;

        // 计划跑完了但顶层物品仍没凑齐（合成失败、没工作站、节点只能收集子材料……）：
        // 记下来免得在"搜不到 → 再追溯"之间打转，并把这一路收集到的材料收进潜影盒。
        boolean unfinished = root != null && ctx.client.player != null
                && ItemUtil.countEverywhere(ctx.client.player, root) < ctx.targetFor(root);
        if (unfinished) {
            ctx.tracedItems.add(root);
        }

        reset();

        // 追踪途中 currentTargetItem 一直指向最后派出去的那个子材料，
        // 交还控制权之前必须还原成顶层物品，否则"这一项够了吗"会检查错对象。
        if (root != null) {
            ctx.currentTargetItem = root;
            ctx.targetNeededTotal = ctx.targetFor(root);
            ctx.currentlyGathered = ItemUtil.countEverywhere(ctx.client.player, root);
        }
        if (unfinished) {
            // 不设"存完直接下一个"：这一步还没决定这个物品是补齐了还是要去搜索。
            tsm.requestTraceStorage(false);
        }
        tsm.onRawMaterialTaskFinished();
    }

    /**
     * 这个采集目标的"应该持有到多少个"。
     *
     * <p>子材料：清单给它的预留量 + 这次合成要消耗的量——合成只能吃超出预留的那部分富余，
     * 所以两者相加才是它最终该有的数量。
     * 顶层物品：就是清单给它的需求总量（缺口由持有量自己补上），不需要再加一次。
     */
    private static int absoluteTarget(GatherContext ctx, Item item, int need, boolean isRoot) {
        int reserved = ctx.targetFor(item);
        return isRoot ? Math.max(reserved, need) : reserved + need;
    }

    /** 整条链做不下去了：跳过顶层物品，并记下来免得反复重试。 */
    private void failChain(GatherContext ctx, TaskStateMachine tsm, Item failedItem) {
        if (failedItem != null) {
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.raw_material_missing", name(failedItem));
        }
        if (this.rootItem != null) {
            ctx.tracedItems.add(this.rootItem);
        }
        reset();
        // 合成没做成，这一路收集来的原材料先存进潜影盒（开了自动存盒才有意义），
        // 免得它们白占背包格子；存不进去就留着，由玩家自己处理。
        tsm.requestTraceStorage(true);
        tsm.skipCurrentItem();
    }

    /** 记下这一轮碰过的物品：供"合成失败后存盒"使用，同时记进跨轮次的材料记忆。 */
    private static void remember(GatherContext ctx, Item item) {
        if (item == null) return;
        ctx.extraStorableItems.add(item);
        MaterialMemory.remember(item);
    }

    /**
     * 子材料在缓存里没找到之后的处置顺序：先看能不能挖，再看向下分解，都不行才整条链失败。
     *
     * @param allowMine false = 刚从挖矿回来（挖不到），不要再挖一次
     */
    private void afterGatherFailed(GatherContext ctx, TaskStateMachine tsm, Item item, boolean allowMine) {
        RawNode node = this.dispatchedNode;
        boolean isRoot = this.dispatchedIsRoot;
        boolean wasExtra = this.dispatchedExtra;
        this.dispatchedItem = null;

        // 先看能不能挖（额外收集来的材料也一样交给 Baritone 试试，startMining 不动 dispatchedExtra 标记）。
        if (allowMine && canMineHere() && !MiningSources.blocksFor(item).isEmpty()) {
            startMining(ctx, tsm, item, this.dispatchedNeed, node, isRoot);
            return;
        }
        this.dispatchedExtra = false;
        if (wasExtra) {
            // 额外收集来的材料挖不到也搜不到：树上没有它的替代路径，这一帧做不下去了。
            failChain(ctx, tsm, item);
            return;
        }
        if (node != null && !node.children().isEmpty() && isSupportedRecipe(node.recipeType())) {
            this.frames.push(new Frame(item, node,
                    absoluteTarget(ctx, item, this.dispatchedNeed, false), false));
            this.stage = Stage.GATHER;
            return;
        }
        failChain(ctx, tsm, item);
    }

    // ---- 世界内挖掘 ----

    private void startMining(GatherContext ctx, TaskStateMachine tsm, Item item, int need,
                             RawNode node, boolean isRoot) {
        List<String> blocks = MiningSources.blocksFor(item);
        if (blocks.isEmpty()) {
            failChain(ctx, tsm, item);
            return;
        }

        remember(ctx, item);
        this.dispatchedItem = item;
        this.dispatchedNode = node;
        this.dispatchedNeed = need;
        this.dispatchedIsRoot = isRoot;
        this.dispatchedTarget = absoluteTarget(ctx, item, need, isRoot);
        this.stage = Stage.MINING;

        // quantity 传 0（不限）：Baritone 数的是"掉落物是否匹配方块"，和我们真正要的物品不一定对得上
        // （例如挖石头掉的是圆石），达标与否由我们自己按物品数量判断。
        this.baritone.mine(blocks.get(0), 0);
        MessageUtil.sendActionBar(ctx.client,
                "playercontrolpp.message.baritone.raw_material_mining", name(item));
    }

    private void miningTick(GatherContext ctx, TaskStateMachine tsm) {
        Item item = this.dispatchedItem;
        if (item == null) {
            finish(ctx, tsm);
            return;
        }

        int have = ItemUtil.countEverywhere(ctx.client.player, item);
        if (have >= this.dispatchedTarget) {
            this.baritone.cancelMining();
            onSubItemDone(ctx, tsm);
            return;
        }

        if (!this.baritone.isMining()) {
            // Baritone 自己收工了（没路径 / 没已知位置）：按"这一项挖不到"继续后面的处置。
            afterGatherFailed(ctx, tsm, item, false);
            return;
        }

        // 只看 Baritone 还在不在干活：挖够、无路可走、已无已知位置它都会自己收工，
        // 用不着本模组再设一个超时——早先那个 1200 tick 的兜底反而会把还在挖的进程截停。
    }

    // ---- 工具 ----

    private void reset() {
        this.stage = Stage.IDLE;
        this.frames.clear();
        this.rootItem = null;
        this.dispatchedItem = null;
        this.dispatchedNode = null;
        this.dispatchedNeed = 0;
        this.dispatchedTarget = 0;
        this.dispatchedIsRoot = false;
        this.dispatchedExtra = false;
        this.unpackItem = null;
        this.craftingWarned = false;
    }

    /** 溯源层数：配置里指定了就用它；-1 = 跟随 LitematList 界面上的层数，读不到用它的默认值。 */
    private static int resolveDepth() {
        int configured = Configs.BaritoneSettings.RAW_MATERIAL_MAX_DEPTH.getIntegerValue();
        if (configured >= 0) return configured;
        int observed = LitematListIntegration.getObservedPlayerDepth();
        return observed >= 0 ? observed : DEFAULT_DEPTH;
    }

    private static boolean canMineHere() {
        return Configs.BaritoneSettings.RAW_MATERIAL_CRAFT_MODE.getOptionListValue()
                == RawMaterialMode.WORLD_SEARCH;
    }

    private static boolean isSupportedRecipe(String recipeType) {
        return "crafting_shaped".equals(recipeType)
                || "crafting_shapeless".equals(recipeType)
                || "stonecutting".equals(recipeType);
    }

    /**
     * 把树上的数量按"我们实际还需要多少个"等比缩放。
     *
     * <p>树里的数量是"从零做出 {@code node.count()} 个"的绝对值，所以
     * {@code 子材料数 × 实际需要 / 本层总数} 就是这次真正要的子材料数（向上取整，
     * 与 LitematList 自己的取整口径一致）。
     */
    private static int scaleCount(int childCount, int need, int parentCount) {
        if (parentCount <= 0) return childCount;
        long scaled = ((long) childCount * need + parentCount - 1) / parentCount;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, scaled));
    }

    private static Item firstChildItem(RawNode node) {
        for (RawNode child : node.children()) {
            Item item = resolveItem(child.itemId());
            if (item != null) return item;
        }
        return null;
    }

    private static Item resolveItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        try {
            return RegistryCompat.item(Identifier.parse(itemId));
        } catch (Throwable e) {
            return null;
        }
    }

    private static String name(Item item) {
        return item == null ? "?" : BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
