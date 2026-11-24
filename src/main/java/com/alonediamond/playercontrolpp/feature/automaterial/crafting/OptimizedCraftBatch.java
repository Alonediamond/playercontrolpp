package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes.SolverRecipe;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes.SolverSnapshot;
import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.config.Configs.BaritoneSettings;
import com.alonediamond.playercontrolpp.config.CraftStationSource;
import com.alonediamond.playercontrolpp.config.RawMaterialMode;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.container.CachedContainerSource;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Balance;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Step;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Stock;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySchedule;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySolver;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.WholeOrderSolver;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.inventory.InventoryCounts;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** 客户端分批冻结这一制作窗口的数据，工作线程只接收求解器的不可变请求。 */
final class OptimizedCraftBatch {
    static final int MAX_OUTPUT_PER_WINDOW = 16;

    private static final int CAPTURE_TICK_LIMIT = 120;
    private static final long GRAPH_NANOS_PER_TICK = 1_000_000L;
    private static final int MAX_GRAPH_ITEMS = 192;
    private static final int MAX_GRAPH_RECIPES = 192;
    private static final int MAX_STOCK_SOURCES = 128;
    private static final int MAX_MODEL_ACTIONS = 256;
    private static final int MAX_HINT_NODES = 256;
    private static final String PLAYER_STOCK = "inventory";
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), work -> {
                Thread thread = new Thread(work, "PlayerControlpp Supply Solver");
                thread.setDaemon(true);
                return thread;
            });

    enum Progress { PENDING, READY, NO_PLAN, LIMIT, STALE, UNAVAILABLE, INVALID_FLOORS }
    private record Output(String id, int depth) {}

    private final ClientLevel level;
    private final LocalPlayer player;
    private final long requirementsRevision;
    private final int depth;
    private final RawMaterialMode traceMode;
    private final CraftStationSource stationMode;
    private final List<String> craftingTables;
    private final List<String> stonecutters;
    private final Set<Item> traceStops;
    private final Item root;
    private final String rootId;
    private final RawNode tree;
    private final boolean wholeOrder;
    private final WholeOrderWait wait;
    private final AtomicReference<SupplyModel.Delivery> candidate = new AtomicReference<>();
    private final boolean preferFinished;
    private boolean checkingFinished;
    private boolean partialDelivery;
    private Map<Item, Integer> promised;
    private SupplyModel.Plan acceptedPlan;
    private final Map<Item, Integer> protectedStock;
    private final Map<Item, Integer> completedReserves;
    private final Set<String> preferKeeping;
    private final List<String> floorSettings;
    private final SupplyModel.Policy policy;
    private final int nodeLimit;
    private final int timeMillis;
    private final boolean tableAvailable;
    private final boolean cutterAvailable;
    private final Map<Item, Balance> initialBalances = new HashMap<>();
    private final int target;
    private final Set<BlockPos> excluded;
    private final BlockPos origin;
    private final SolverSnapshot recipes;
    private final Deque<Output> graph = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final List<SupplyModel.Recipe> modelRecipes = new ArrayList<>();
    private final List<Stock> stock = new ArrayList<>();
    private ChestTrackerIntegration.LooseStockScan scan;
    private FutureTask<SupplyModel.Delivery> future;
    private int ticks;
    private boolean feasibleAtLimit;
    private boolean withinFivePercent;
    private List<Step> steps = List.of();
    private SupplySchedule schedule;
    private WholeCapture wholeCapture;
    private Map<Item, Integer> targets;

    OptimizedCraftBatch(GatherContext ctx, Item root, RawNode tree, int target, Set<BlockPos> excluded) {
        this(ctx, root, tree, target, excluded, null, null);
    }

    OptimizedCraftBatch(GatherContext ctx, Item root, RawNode tree, int target, Set<BlockPos> excluded,
                        Map<Item, Integer> protectedStock, WholeOrderWait wait) {
        this.wait = wait;
        this.level = ctx.client.level;
        this.player = ctx.client.player;
        this.requirementsRevision = ctx.requirements.revision();
        this.depth = configuredDepth();
        this.traceMode = (RawMaterialMode) BaritoneSettings.RAW_MATERIAL_CRAFT_MODE.getOptionListValue();
        this.stationMode = (CraftStationSource) BaritoneSettings.CRAFT_STATION_SOURCE.getOptionListValue();
        this.wholeOrder = protectedStock != null;
        this.preferFinished = BaritoneSettings.SOLVER_PREFER_FINISHED.getBooleanValue();
        this.checkingFinished = wholeOrder && preferFinished;
        this.protectedStock = wholeOrder ? new HashMap<>(protectedStock) : Map.of();
        this.completedReserves = wholeOrder ? Map.copyOf(protectedStock) : Map.of();
        this.policy = selectedPolicy();
        this.preferKeeping = Set.copyOf(BaritoneSettings.SOLVER_PREFER_KEEPING.getStrings());
        this.floorSettings = List.copyOf(BaritoneSettings.SOLVER_STOCK_FLOORS.getStrings());
        this.nodeLimit = BaritoneSettings.SOLVER_WINDOW_NODES.getIntegerValue();
        this.timeMillis = BaritoneSettings.SOLVER_WINDOW_MILLIS.getIntegerValue();
        this.tableAvailable = stationAvailable(CraftStationManager.Kind.CRAFTING_TABLE);
        this.cutterAvailable = stationAvailable(CraftStationManager.Kind.STONECUTTER);
        this.craftingTables = List.copyOf(BaritoneSettings.CRAFTING_TABLE_POSITIONS.getStrings());
        this.stonecutters = List.copyOf(BaritoneSettings.STONECUTTER_POSITIONS.getStrings());
        this.traceStops = LitematListIntegration.getInstance().rawTraceStops();
        this.root = root;
        this.rootId = id(root);
        this.tree = tree;
        this.target = target;
        this.excluded = Set.copyOf(excluded);
        this.origin = this.player.blockPosition();
        this.recipes = CraftingRecipes.captureForSolver(ctx.client);
        if (wholeOrder) {
            targets = WholeOrderStock.targets(ctx.requirements.entries());
            for (Item item : targets.keySet()) {
                enqueue(id(item), 0);
                if (checkingFinished) items.put(id(item), item);
            }
        }
    }

    private boolean stationAvailable(CraftStationManager.Kind kind) {
        return stationMode != CraftStationSource.INVENTORY && CraftStationManager.getInstance().anyInDimension(level, kind)
                || stationMode != CraftStationSource.WORLD && QuickShulkerIntegration.getInstance().isLoaded()
                && ItemUtil.countLoose(player, kind.item) > 0;
    }

    private static SupplyModel.Policy selectedPolicy() {
        return BaritoneSettings.SOLVER_FEWEST_OPERATIONS.getBooleanValue()
                ? SupplyModel.Policy.FEWEST_OPERATIONS : SupplyModel.Policy.SAVE_STOCK;
    }

    private static int configuredDepth() {
        return LitematListIntegration.resolveRawDepth(BaritoneSettings.RAW_MATERIAL_MAX_DEPTH.getIntegerValue());
    }

    boolean isCurrent(GatherContext ctx) {
        return ctx.client.level == level && ctx.client.player == player
                && !player.isCreative() && !player.isSpectator()
                && ctx.requirements.revision() == requirementsRevision
                && BaritoneSettings.USE_SUPPLY_SOLVER.getBooleanValue()
                && BaritoneSettings.SOLVE_WHOLE_LIST.getBooleanValue() == wholeOrder
                && selectedPolicy() == policy && preferKeeping.equals(Set.copyOf(BaritoneSettings.SOLVER_PREFER_KEEPING.getStrings()))
                && (!wholeOrder || floorSettings.equals(BaritoneSettings.SOLVER_STOCK_FLOORS.getStrings())
                && preferFinished == BaritoneSettings.SOLVER_PREFER_FINISHED.getBooleanValue())
                && (wholeOrder || nodeLimit == BaritoneSettings.SOLVER_WINDOW_NODES.getIntegerValue()
                && timeMillis == BaritoneSettings.SOLVER_WINDOW_MILLIS.getIntegerValue())
                && BaritoneSettings.RAW_MATERIAL_CRAFT_MODE.getOptionListValue() == traceMode
                && BaritoneSettings.CRAFT_STATION_SOURCE.getOptionListValue() == stationMode
                && BaritoneSettings.CRAFTING_TABLE_POSITIONS.getStrings().equals(craftingTables)
                && BaritoneSettings.STONECUTTER_POSITIONS.getStrings().equals(stonecutters)
                && configuredDepth() == depth && (recipes == null || recipes.isCurrent(ctx.client))
                && (!wholeOrder || traceStops == null || traceStopsCurrent());
    }

    List<Step> steps() { return steps; }
    boolean feasibleAtLimit() { return feasibleAtLimit; }
    boolean withinFivePercent() { return withinFivePercent; }
    boolean partialDelivery() { return partialDelivery; }

    boolean isComplete(GatherContext ctx) {
        if (targets == null) return ctx.countHeld(root) >= target;
        Map<Item, Integer> delivery = partialDelivery ? promised : targets;
        return delivery.entrySet().stream().allMatch(entry -> ctx.countHeld(entry.getKey()) >= entry.getValue());
    }

    Set<Item> targetItems() {
        return targets == null ? Set.of(root) : targets.keySet();
    }

    CraftingRecipes.Plan bind(GatherContext ctx, CraftBatchActions.Craft action) {
        if (!traceStopsCurrent()) return null;
        return recipes.bind(ctx.client, action.recipe().id(), action.grid());
    }

    private boolean traceStopsCurrent() {
        return traceStops.equals(LitematListIntegration.getInstance().rawTraceStops());
    }

    Progress advance(GatherContext ctx) {
        if (!isCurrent(ctx)) return Progress.STALE;
        if (future != null) return poll(ctx);
        if (wholeOrder && wait.expired()) return Progress.LIMIT;
        if (!wholeOrder && ++ticks > CAPTURE_TICK_LIMIT) return Progress.LIMIT;
        if (recipes == null || traceStops == null || depth < 0 || depth == 0 && !wholeOrder) return Progress.UNAVAILABLE;
        if (!checkingFinished && !recipes.tick(ctx.client)) return Progress.PENDING;
        if (targets == null) {
            List<MaterialItemEntry> candidates = ctx.requirements.entries().stream()
                    .filter(entry -> !traceStops.contains(entry.item) && !ctx.tracedItems.contains(entry.item))
                    .toList();
            targets = windowTargets(root, target, candidates, item -> recipes.recipesFor(id(item)), ctx::countHeld);
            for (Item item : targets.keySet()) enqueue(id(item), 0);
        }
        if (scan == null) {
            if (!checkingFinished) {
                Progress progress = captureGraph();
                if (progress != Progress.READY) return progress;
            }
            scan = new CachedContainerSource().scanLoose(ctx.client, origin, Set.copyOf(items.values()));
            if (scan == ChestTrackerIntegration.UNAVAILABLE_LOOSE_SCAN) return Progress.UNAVAILABLE;
        }
        List<ChestTrackerIntegration.Stock> cached = scan.tick();
        if (!scan.valid()) return Progress.STALE;
        if (cached == null) return Progress.PENDING;
        return wholeOrder ? submitWholeOrder(ctx, cached) : submit(ctx, cached);
    }

    /** 共用固定原料的小目标放入同一窗口，保持当前目标优先并限制窗口的总缺口。 */
    static Map<Item, Integer> windowTargets(Item root, int target, List<MaterialItemEntry> candidates,
                                           Function<Item, List<SolverRecipe>> recipes, ToIntFunction<Item> held) {
        Map<Item, Integer> selected = new LinkedHashMap<>();
        selected.put(root, target);
        int room = MAX_OUTPUT_PER_WINDOW - Math.max(0, target - held.applyAsInt(root));
        if (room <= 0) return selected;
        Set<String> required = fixedInputs(recipes.apply(root));
        if (required.isEmpty()) return selected;
        for (MaterialItemEntry candidate : candidates) {
            if (candidate.item == root || Collections.disjoint(required, fixedInputs(recipes.apply(candidate.item)))) continue;
            int gap = candidate.neededCount - held.applyAsInt(candidate.item);
            if (gap <= 0 || gap > room) continue;
            selected.put(candidate.item, candidate.neededCount);
            room -= gap;
            if (room == 0) break;
        }
        return selected;
    }

    private static Set<String> fixedInputs(List<SolverRecipe> recipes) {
        Set<String> required = null;
        for (SolverRecipe recipe : recipes) {
            Set<String> fixed = new HashSet<>();
            for (List<String> slot : recipe.grid()) {
                if (slot.size() == 1) fixed.add(slot.getFirst());
            }
            if (required == null) required = fixed;
            else required.retainAll(fixed);
            if (required.isEmpty()) return Set.of();
        }
        return required == null ? Set.of() : required;
    }

    private Progress captureGraph() {
        long start = System.nanoTime();
        while (!graph.isEmpty() && System.nanoTime() - start < GRAPH_NANOS_PER_TICK) {
            Output output = graph.removeFirst();
            Item item = RegistryCompat.item(Identifier.parse(output.id()));
            if (item == null || item == Items.AIR) return Progress.UNAVAILABLE;
            items.put(output.id(), item);
            if (output.depth() >= depth || traceStops.contains(item)) continue;
            for (SolverRecipe recipe : recipes.recipesFor(output.id())) {
                if (wholeOrder && !("stonecutting".equals(recipe.type()) ? cutterAvailable : tableAvailable)) continue;
                if (!wholeOrder && modelRecipes.size() >= MAX_GRAPH_RECIPES) return Progress.LIMIT;
                List<SupplyModel.Slot> slots = new ArrayList<>(recipe.grid().size());
                for (List<String> choices : recipe.grid()) {
                    slots.add(new SupplyModel.Slot(choices));
                    for (String choice : choices) {
                        enqueue(choice, output.depth() + 1);
                        if (!wholeOrder && queued.size() > MAX_GRAPH_ITEMS) return Progress.LIMIT;
                    }
                }
                modelRecipes.add(new SupplyModel.Recipe(recipe.id(), recipe.type(), recipe.output(), recipe.yield(), slots));
            }
        }
        if (!graph.isEmpty()) return Progress.PENDING;
        return !wholeOrder && recipes.recipesFor(rootId).isEmpty() ? Progress.UNAVAILABLE : Progress.READY;
    }

    private void enqueue(String item, int depth) {
        if (queued.add(item)) graph.addLast(new Output(item, depth));
    }

    private Progress submit(GatherContext ctx, List<ChestTrackerIntegration.Stock> cached) {
        Function<Item, Balance> current = currentBalances(ctx);
        int gap = target - current.apply(root).owned();
        if (gap <= 0 || gap > MAX_OUTPUT_PER_WINDOW) return Progress.STALE;
        int[] hintNodes = { MAX_HINT_NODES };
        SupplyModel.Hint hint = hint(tree, depth, hintNodes);
        if (hint == null) return Progress.UNAVAILABLE;
        List<SupplyModel.Goal> goals = new ArrayList<>();
        goals.add(new SupplyModel.Goal(rootId, gap, hint));
        Set<Item> cachedItems = new HashSet<>();
        for (ChestTrackerIntegration.Stock source : cached) {
            if (!excluded.contains(source.position())) cachedItems.add(source.item());
        }
        int room = MAX_OUTPUT_PER_WINDOW - gap;
        // 有成品货源的同组条目留给正常采集。选中目标继续以清单的绝对数量核对。
        Iterator<Map.Entry<Item, Integer>> pending = targets.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Item, Integer> entry = pending.next();
            Item item = entry.getKey();
            if (item == root) continue;
            int missing = entry.getValue() - current.apply(item).owned();
            if (missing <= 0 || missing > room || cachedItems.contains(item)) {
                pending.remove();
                continue;
            }
            goals.add(new SupplyModel.Goal(id(item), missing, null));
            room -= missing;
        }
        for (Map.Entry<String, Item> entry : items.entrySet()) {
            if (targets.containsKey(entry.getValue())) continue;
            int usable = current.apply(entry.getValue()).usable();
            if (usable > 0) stock.add(new Stock(new SupplyModel.Source(PLAYER_STOCK, entry.getKey(), usable, 0), null));
        }
        // 清单本来就缺的物品不从缓存计为可消耗库存。执行仍逐次按自有预留核对。
        for (ChestTrackerIntegration.Stock source : cached) {
            if (targets.containsKey(source.item()) || excluded.contains(source.position())) continue;
            Balance balance = current.apply(source.item());
            if (balance.owned() < balance.reserve()) continue;
            if (stock.size() >= MAX_STOCK_SOURCES) return Progress.LIMIT;
            stock.add(cachedStock(source));
        }
        return dispatch(goals, Map.of());
    }

    private Progress submitWholeOrder(GatherContext ctx, List<ChestTrackerIntegration.Stock> cached) {
        if (wholeCapture == null) {
            try {
                wholeCapture = new WholeCapture(ctx, cached, WholeOrderStock.parseFloors(floorSettings));
            } catch (IllegalArgumentException invalid) {
                return Progress.INVALID_FLOORS;
            }
        }
        return wholeCapture.advance();
    }

    /** 背包在同一 tick 冻结；大仓库的逐行转换延续到后续 tick。求解结束时再与玩家实际持有量对账。 */
    private final class WholeCapture {
        private final Function<Item, Balance> inventory;
        private final Iterator<Map.Entry<String, Item>> catalogItems = items.entrySet().iterator();
        private final Iterator<ChestTrackerIntegration.Stock> rows;
        private final List<SupplyModel.Goal> goals = new ArrayList<>();
        private final Map<String, Long> totals = new HashMap<>();
        private final Map<String, Integer> floors;

        WholeCapture(GatherContext ctx, List<ChestTrackerIntegration.Stock> cached, Map<String, Integer> floors) {
            this.inventory = balances(ctx, item -> 0);
            this.rows = cached.iterator();
            this.floors = floors;
            // 唯一的随身工作站需留在背包里才能继续开站。带名字的站也可以打开，此时无需另留普通的一件。
            for (CraftStationManager.Kind kind : CraftStationManager.Kind.values()) {
                Balance balance = inventory.apply(kind.item);
                boolean portableOnly = stationMode != CraftStationSource.WORLD && QuickShulkerIntegration.getInstance().isLoaded()
                        && (stationMode == CraftStationSource.INVENTORY || !CraftStationManager.getInstance().anyInDimension(level, kind));
                if (portableOnly && balance.plain() > 0 && ItemUtil.countLoose(player, kind.item) == balance.plain()) {
                    protectedStock.merge(kind.item, balance.owned() - balance.plain() + 1, Math::max);
                }
            }
        }

        Progress advance() {
            long started = System.nanoTime();
            do {
                if (catalogItems.hasNext()) {
                    Map.Entry<String, Item> entry = catalogItems.next();
                    capturePlayerItem(entry.getKey(), entry.getValue());
                } else if (rows.hasNext()) {
                    ChestTrackerIntegration.Stock row = rows.next();
                    if (!excluded.contains(row.position())) addStock(cachedStock(row));
                } else {
                    Map<String, Integer> effective = new HashMap<>();
                    floors.forEach((id, floor) -> {
                        Balance balance = initialBalances.get(items.get(id));
                        if (balance != null) effective.put(id, WholeOrderStock.additionalFloor(balance, floor, totals.getOrDefault(id, 0L)));
                    });
                    if (checkingFinished && !WholeOrderStock.finishedCover(goals, totals, effective)) {
                        // 只扫描成品的结果不能用于原料分配。重新冻结完整快照，期间没有交互或库存扣账。
                        checkingFinished = false;
                        initialBalances.clear();
                        protectedStock.clear();
                        protectedStock.putAll(completedReserves);
                        stock.clear();
                        scan = null;
                        wholeCapture = null;
                        return Progress.PENDING;
                    }
                    return dispatch(goals, effective);
                }
            } while (System.nanoTime() - started < GRAPH_NANOS_PER_TICK);
            return Progress.PENDING;
        }

        private void capturePlayerItem(String id, Item item) {
            WholeOrderStock allocation = WholeOrderStock.allocate(inventory.apply(item), targets.getOrDefault(item, 0),
                    protectedStock.getOrDefault(item, 0));
            Balance balance = allocation.balance();
            if (balance.reserve() > 0) protectedStock.put(item, balance.reserve());
            initialBalances.put(item, balance);
            if (allocation.missing() > 0) goals.add(new SupplyModel.Goal(id, allocation.missing(), null));
            if (balance.usable() > 0) addStock(new Stock(new SupplyModel.Source(PLAYER_STOCK, id, balance.usable(), 0), null));
        }

        private void addStock(Stock entry) {
            stock.add(entry);
            totals.merge(entry.model().item(), (long) entry.model().count(), Math::addExact);
        }
    }

    private Stock cachedStock(ChestTrackerIntegration.Stock row) {
        int distance = (int) Math.sqrt(row.position().distSqr(origin));
        int cost = 1 + Math.min(distance, 1000);
        return new Stock(new SupplyModel.Source(row.position().toShortString(), id(row.item()), row.count(), cost), row.position());
    }

    private Progress dispatch(List<SupplyModel.Goal> goals, Map<String, Integer> floors) {
        Set<String> stopIds = new HashSet<>();
        for (Item item : traceStops) stopIds.add(id(item));
        SupplyModel.Request request = new SupplyModel.Request(
                new SupplyModel.Catalog(modelRecipes), stock.stream().map(Stock::model).toList(),
                goals, floors, preferKeeping, stopIds, checkingFinished ? 0 : depth, policy, SupplyModel.Mode.PREPARE);
        int nodes = nodeLimit;
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeMillis);
        boolean useFinished = preferFinished;
        if (wholeOrder && wait.seconds() > 0) {
            WholeOrderWait deadline = wait;
            AtomicReference<SupplyModel.Delivery> latest = candidate;
            future = new FutureTask<>(() -> WholeOrderSolver.planForGathering(request, useFinished, deadline::expired, latest::set));
        } else if (wholeOrder) {
            future = new FutureTask<>(() -> WholeOrderSolver.planForGathering(request, useFinished));
        } else {
            future = new FutureTask<>(() -> new SupplyModel.Delivery(SupplySolver.solve(request, nodes, nanos), Map.of(), false));
        }
        WORKER.execute(future);
        return Progress.PENDING;
    }

    private Progress poll(GatherContext ctx) {
        SupplyModel.Delivery delivery;
        if (!future.isDone() && wholeOrder && wait.expired() && future.cancel(true)) {
            // 工作线程可能还在排队或计算一个节点。只取该快照已发布的完整候选，无需阻塞客户端。
            WORKER.remove(future);
            delivery = candidate.get();
            if (delivery == null) return Progress.LIMIT;
        } else if (!future.isDone()) {
            return Progress.PENDING;
        } else if (future.state() == Future.State.FAILED) {
            Playercontrolpp.LOGGER.warn("Raw material solver failed", future.exceptionNow());
            return Progress.UNAVAILABLE;
        } else if (future.state() == Future.State.SUCCESS) {
            delivery = future.resultNow();
        } else {
            return Progress.STALE;
        }
        if (!traceStopsCurrent()) return Progress.STALE;
        Function<Item, Balance> current = currentBalances(ctx);
        if (wholeOrder && (initialBalances.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(current.apply(entry.getKey())))
                || tableAvailable != stationAvailable(CraftStationManager.Kind.CRAFTING_TABLE)
                || cutterAvailable != stationAvailable(CraftStationManager.Kind.STONECUTTER))) return Progress.STALE;
        SupplyModel.Result result = delivery.result();
        SupplyModel.Plan plan = CraftBatchActions.executable(result);
        if (plan == null) {
            return result.status() == SupplyModel.Status.UNKNOWN_LIMIT ? Progress.LIMIT : Progress.NO_PLAN;
        }
        if (!wholeOrder && plan.actions().size() > MAX_MODEL_ACTIONS) return Progress.LIMIT;
        List<Step> translated = CraftBatchActions.translate(plan, stock, items);
        partialDelivery = wholeOrder && delivery.partial();
        promised = partialDelivery ? WholeOrderStock.partialTargets(delivery.units(), items, initialBalances) : targets;
        if (!CraftBatchActions.canExecute(translated, current, promised)) return Progress.STALE;
        acceptedPlan = plan;
        CraftInventory inventory = new CraftInventory(player.getInventory()::getItem,
                BaritoneSettings.SOLVER_PLACEMENT_LIMIT.getIntegerValue());
        if (wholeOrder && !inventory.fits(translated)) {
            schedule = new SupplySchedule(plan);
        } else {
            steps = CraftBatchActions.group(translated, inventory);
        }
        feasibleAtLimit = result.status() == SupplyModel.Status.FEASIBLE_LIMIT;
        withinFivePercent = result.status() == SupplyModel.Status.WITHIN_FIVE_PERCENT;
        return Progress.READY;
    }

    boolean hasSegments() { return schedule != null && !schedule.isEmpty(); }

    RemainderStock remainderStock(GatherContext ctx, boolean currentSnapshot) {
        if (floorSettings.isEmpty() || !currentSnapshot || !isCurrent(ctx)) return RemainderStock.withoutSnapshot(ctx);
        Map<Item, Long> warehouse = new HashMap<>();
        Map<Item, Integer> usedFromPlayer = new HashMap<>();
        for (Stock row : stock) {
            if (row.container() != null) warehouse.merge(items.get(row.model().item()), (long) row.model().count(), Long::sum);
        }
        if (acceptedPlan != null) {
            for (SupplyModel.Action action : acceptedPlan.actions()) {
                if (!(action instanceof SupplyModel.Take take)) continue;
                Item item = items.get(take.item());
                if (stock.get(take.source()).container() == null) usedFromPlayer.merge(item, take.count(), Math::addExact);
                else warehouse.merge(item, -(long) take.count(), Long::sum);
            }
        }
        return RemainderStock.capture(item -> {
            int live = ItemUtil.countPlainLoose(player, item);
            Balance before = initialBalances.get(item);
            return before == null ? live : Math.min(live, before.plain() - usedFromPlayer.getOrDefault(item, 0));
        }, item -> warehouse.getOrDefault(item, 0L));
    }

    List<Step> nextSegment() {
        CraftInventory inventory = new CraftInventory(player.getInventory()::getItem,
                BaritoneSettings.SOLVER_PLACEMENT_LIMIT.getIntegerValue());
        return CraftBatchActions.nextSegment(schedule, stock, items, inventory);
    }

    void cancel() {
        if (future != null) {
            future.cancel(true);
            WORKER.remove(future);
            future = null;
        }
    }

    Function<Item, Balance> currentBalances(GatherContext ctx) {
        return balances(ctx, wholeOrder ? item -> protectedStock.getOrDefault(item, 0) : ctx::targetFor);
    }

    static Function<Item, Balance> balances(GatherContext ctx, ToIntFunction<Item> reserve) {
        Inventory inventory = ctx.client.player.getInventory();
        InventoryCounts owned = InventoryCounts.capture(inventory::getItem, ctx.extractionBoxSlots);
        Map<Item, Integer> plain = new HashMap<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemUtil.isPlainLoose(stack)) plain.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        Map<Item, Balance> current = new HashMap<>();
        return item -> current.computeIfAbsent(item, key -> new Balance(
                owned.owned(key), plain.getOrDefault(key, 0), reserve.applyAsInt(key)));
    }

    private static SupplyModel.Hint hint(RawNode node, int depth, int[] remainingNodes) {
        if (remainingNodes[0]-- <= 0 || node.count() <= 0) return null;
        List<SupplyModel.Hint> children = new ArrayList<>();
        if (depth > 0 && CraftingRecipes.isSupportedType(node.recipeType())) {
            for (RawNode child : node.children()) {
                SupplyModel.Hint converted = hint(child, depth - 1, remainingNodes);
                if (converted == null) return null;
                children.add(converted);
            }
        }
        return new SupplyModel.Hint(node.itemId(), node.count(), node.recipeType(), children);
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
