package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.BatchLayout.Group;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.BatchLayout.Share;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Delivery;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Mode;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Request;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Score;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Status;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongConsumer;

/**
 * 对整单按允许的制作层数倒推。第 d 层的每种需求可由本层多个配方和上一层的现货共同满足。
 * 同层制作只消费上一层的物品，所以顺序回放一定有种子，互转与跨目标余料都无需特判。
 */
public final class WholeOrderSolver {
    // 记忆表满时继续搜索；这个值只影响重复工作量，不影响可交付的计划或最优性。
    private static final int MEMO_ENTRIES = 32_768;
    private record Stage(int level, Map<String, Long> due, Map<String, Long> earlier, int recipeIndex) {}
    private record History(List<? extends Action> actions, History later) {}
    private record Path(long crafts, History history) {}
    private record BoxBound(long largest, int cheapest, boolean exclusive) {}
    private final Request request;
    private final Map<String, List<Integer>> sources = new HashMap<>();
    private final Map<String, BoxBound> boxBounds = new HashMap<>();
    private final Map<String, List<BatchLayout>> recipes = new HashMap<>();
    private final List<Map<String, Long>> capacities = new ArrayList<>();
    private final List<Set<BatchLayout>> byHeight = new ArrayList<>();
    private final Map<Stage, Long> cheapestPrefix = new HashMap<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private Map<String, Long> delivery;
    private long[] laterSourceUnits;
    private Plan best;
    private long explored;

    private WholeOrderSolver(Request request) { this.request = request; }

    public static Result solve(Request request) {
        if (request.mode() != Mode.PREPARE) throw new IllegalArgumentException("整单精确求解需要成品目标");
        WholeOrderSolver solver = new WholeOrderSolver(request);
        if (solver.index()) solver.search(solver.goals(), request.depth(), false);
        return solver.result();
    }

    /** 只在全单已证不可行后缩小交付量。每次可行性试算都重新联合分配所有已选目标，直到最后才选成本最低的方案。 */
    public static Delivery planForGathering(Request request, boolean preferFinished) {
        if (request.mode() != Mode.PREPARE) throw new IllegalArgumentException("整单精确求解需要成品目标");
        WholeOrderSolver solver = new WholeOrderSolver(request);
        Map<String, Long> wanted = solver.goals();
        if (!solver.index()) return new Delivery(solver.result(), Map.of(), false);
        if (solver.search(wanted, solver.deliveryDepth(wanted, preferFinished), false) || cancelled()) {
            return new Delivery(solver.result(), wanted, false);
        }
        Map<String, Long> partial = solver.attainable(wanted);
        if (!partial.isEmpty() && !cancelled()) solver.search(partial, solver.deliveryDepth(partial, preferFinished), false);
        return new Delivery(solver.result(), partial, true);
    }

    private int deliveryDepth(Map<String, Long> goals, boolean preferFinished) {
        return preferFinished && covered(goals, 0) ? 0 : request.depth();
    }

    private boolean covered(Map<String, Long> goals, int level) {
        return goals.entrySet().stream().allMatch(entry ->
                entry.getValue() <= capacities.get(level).getOrDefault(entry.getKey(), 0L));
    }

    private Map<String, Long> goals() {
        Map<String, Long> goals = new LinkedHashMap<>();
        for (Goal goal : request.goals()) goals.merge(goal.item(), (long) goal.units(), Math::addExact);
        return goals;
    }

    private Map<String, Long> attainable(Map<String, Long> wanted) {
        Map<String, Long> clipped = new LinkedHashMap<>();
        wanted.forEach((item, units) -> {
            long maximum = Math.min(units, capacities.getLast().getOrDefault(item, 0L));
            if (maximum > 0) clipped.put(item, maximum);
        });
        if (clipped.isEmpty() || cancelled()) return Map.of();
        if (!clipped.equals(wanted) && (covered(clipped, 0) || search(clipped, request.depth(), true))) return clipped;

        // 共享原料时，沿清单顺序依次最大化可交付数量。能交付 N 件就能交付更少，可用二分探测。
        Map<String, Long> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : clipped.entrySet()) {
            if (cancelled()) break;
            String item = entry.getKey();
            long high = entry.getValue();
            kept.put(item, high);
            if (search(kept, request.depth(), true)) continue;
            long low = 0;
            high--;
            while (low < high && !cancelled()) {
                long probe = low + (high - low + 1) / 2;
                kept.put(item, probe);
                if (search(kept, request.depth(), true)) low = probe;
                else high = probe - 1;
            }
            if (low == 0) kept.remove(item);
            else kept.put(item, low);
        }
        return kept;
    }

    private boolean search(Map<String, Long> goals, int level, boolean firstFeasible) {
        best = null;
        pending.clear();
        cheapestPrefix.clear();
        delivery = new TreeMap<>(goals);
        // 一项连乐观上界都达不到就能判整单无解，避免先枚举清单前面的大标签配方。
        if (!covered(delivery, level)) return false;
        Stage initial = new Stage(level, delivery, Map.of(), 0);
        pending.push(() -> expand(initial, new Path(0, null)));
        while (!pending.isEmpty() && !cancelled() && !(firstFeasible && best != null)) {
            explored++;
            pending.pop().run();
        }
        return best != null && !cancelled();
    }

    private Result result() {
        if (cancelled()) return new Result(Status.UNKNOWN_LIMIT, null, explored, List.of());
        return new Result(best == null ? Status.NO_PLAN_IN_MODEL : Status.BEST_IN_MODEL, best, explored, List.of());
    }

    private static boolean cancelled() { return Thread.currentThread().isInterrupted(); }

    private boolean index() {
        Map<String, Long> stock = new HashMap<>();
        for (int i = 0; i < request.stock().size(); i++) {
            if (cancelled()) return false;
            Source source = request.stock().get(i);
            if (source.count() == 0) continue;
            stock.merge(source.item(), (long) source.count(), Math::addExact);
            sources.computeIfAbsent(source.item(), key -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, Integer> floor : request.floors().entrySet()) {
            if (stock.merge(floor.getKey(), -(long) floor.getValue(), Math::addExact) < 0) return false;
        }
        laterSourceUnits = new long[request.stock().size()];
        Map<String, String> firstItem = new HashMap<>();
        Set<String> sharedBoxes = new HashSet<>();
        for (Source row : request.stock()) {
            if (row.visitCost() == 0 || row.count() == 0) continue;
            String previous = firstItem.putIfAbsent(row.box(), row.item());
            if (previous != null && !previous.equals(row.item())) sharedBoxes.add(row.box());
        }
        for (Map.Entry<String, List<Integer>> source : sources.entrySet()) {
            Map<String, Long> perBox = new HashMap<>();
            long later = 0;
            int cheapest = Integer.MAX_VALUE;
            long largest = 0;
            boolean exclusive = true;
            for (int row : source.getValue().reversed()) {
                if (cancelled()) return false;
                Source location = request.stock().get(row);
                laterSourceUnits[row] = later;
                later += location.count();
                if (location.visitCost() == 0) continue;
                largest = Math.max(largest, perBox.merge(location.box(), (long) location.count(), Long::sum));
                cheapest = Math.min(cheapest, location.visitCost());
                exclusive &= !sharedBoxes.contains(location.box());
            }
            boxBounds.put(source.getKey(), new BoxBound(largest, cheapest, exclusive));
        }
        capacities.add(stock);
        Set<String> previousHeight = new HashSet<>();
        stock.forEach((item, count) -> { if (count > 0) previousHeight.add(item); });
        byHeight.add(Set.of());
        for (Recipe recipe : request.catalog().recipes()) {
            if (!request.stopAt().contains(recipe.output())) {
                recipes.computeIfAbsent(recipe.output(), key -> new ArrayList<>()).add(new BatchLayout(recipe));
            }
        }
        // 各配方独占上一层全部库存来估算。共用原料会被重复计算，因此只可用作「最多可有」的剪枝上界。
        for (int layer = 1; layer <= request.depth(); layer++) {
            Map<String, Long> previous = capacities.getLast();
            Map<String, Long> next = new HashMap<>(stock);
            Set<String> thisHeight = new HashSet<>();
            Set<BatchLayout> layouts = new HashSet<>();
            for (List<BatchLayout> alternatives : recipes.values()) {
                if (cancelled()) return false;
                for (BatchLayout layout : alternatives) {
                    long operations = possible(layout, previous, Map.of());
                    if (operations > 0 && layout.groups.stream().anyMatch(group ->
                            group.choices().stream().anyMatch(previousHeight::contains))) {
                        layouts.add(layout);
                        thisHeight.add(layout.recipe.output());
                    }
                    long yield = layout.recipe.yield();
                    long output = operations > Long.MAX_VALUE / yield ? Long.MAX_VALUE : operations * yield;
                    next.merge(layout.recipe.output(), output, WholeOrderSolver::saturatedAdd);
                }
            }
            capacities.add(next);
            byHeight.add(layouts);
            previousHeight.clear();
            previousHeight.addAll(thisHeight);
        }
        return true;
    }

    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private static long room(Map<String, Long> upper, Map<String, Long> used, String item) {
        return upper.getOrDefault(item, 0L) - used.getOrDefault(item, 0L);
    }

    private static long possible(BatchLayout layout, Map<String, Long> upper, Map<String, Long> used) {
        long limit = Long.MAX_VALUE;
        for (Group group : layout.groups) {
            long sum = 0;
            for (String item : group.choices()) sum = saturatedAdd(sum, room(upper, used, item));
            limit = Math.min(limit, sum / group.slots().size());
        }
        return limit;
    }

    private static Map<String, Long> change(Map<String, Long> original, String item, long delta) {
        Map<String, Long> copy = new TreeMap<>(original);
        if (copy.merge(item, delta, Math::addExact) == 0) copy.remove(item);
        return copy;
    }

    private void expand(Stage stage, Path path) {
        if (best != null && new Score(0, 0, path.crafts).compare(best.score(), request.policy()) >= 0) return;
        if (stage.due.isEmpty()) {
            if (stage.earlier.isEmpty()) {
                chooseSources(Map.of(), path);
            } else {
                pending.push(() -> expand(new Stage(stage.level - 1, stage.earlier, Map.of(), 0), path));
            }
            return;
        }
        if (stage.level == 0) {
            chooseSources(stage.due, path);
            return;
        }
        Long prior = cheapestPrefix.get(stage);
        if (prior != null && prior <= path.crafts) return;
        if (prior != null || cheapestPrefix.size() < MEMO_ENTRIES) cheapestPrefix.put(stage, path.crafts);

        String item = stage.due.keySet().iterator().next();
        long units = stage.due.get(item);
        if (units > capacities.get(stage.level).getOrDefault(item, 0L)) return;
        List<BatchLayout> alternatives = recipes.getOrDefault(item, List.of());
        Map<String, Long> previous = capacities.get(stage.level - 1);
        if (stage.recipeIndex == alternatives.size()) {
            if (units > room(previous, stage.earlier, item)) return;
            Stage deferred = new Stage(stage.level, change(stage.due, item, -units), change(stage.earlier, item, units), 0);
            pending.push(() -> expand(deferred, path));
            return;
        }

        BatchLayout layout = alternatives.get(stage.recipeIndex);
        // 把每次制作放在其最深输入之后的第一层即可。单步「原木制板」不必枚举把同一批制板推迟几层。
        if (!byHeight.get(stage.level).contains(layout)) {
            Stage skip = new Stage(stage.level, stage.due, stage.earlier, stage.recipeIndex + 1);
            pending.push(() -> expand(skip, path));
            return;
        }
        int yield = layout.recipe.yield();
        long max = Math.min(Math.ceilDiv(units, yield), possible(layout, previous, stage.earlier));
        long min = 0;
        if (stage.recipeIndex + 1 == alternatives.size()) {
            min = Math.ceilDiv(Math.max(0, units - room(previous, stage.earlier, item)), yield);
        }
        // 不需要额外做一整次无消费者的制作。部分用现货、混用其他配方、末次向上取整都仍在区间内。
        choices(min, max, count -> {
            long supplied = Math.min(units, Math.multiplyExact(count, yield));
            int nextRecipe = supplied == units ? 0 : stage.recipeIndex + 1;
            Stage next = new Stage(stage.level, change(stage.due, item, -supplied), stage.earlier, nextRecipe);
            if (count == 0) expand(next, path);
            else bindGroup(layout, count, 0, List.of(), next, path);
        });
    }

    private void bindGroup(BatchLayout layout, long operations, int index, List<List<Share>> assigned, Stage stage, Path path) {
        if (index == layout.groups.size()) {
            Path production = new Path(Math.addExact(path.crafts, operations), new History(layout.bind(operations, assigned), path.history));
            pending.push(() -> expand(stage, production));
            return;
        }
        Group group = layout.groups.get(index);
        long units = Math.multiplyExact(operations, group.slots().size());
        distribute(layout, operations, index, assigned, stage, path, 0, units, List.of());
    }

    private void distribute(BatchLayout layout, long operations, int groupIndex, List<List<Share>> assigned,
                            Stage stage, Path path, int index, long units, List<Share> shares) {
        if (units == 0) {
            List<List<Share>> filled = new ArrayList<>(assigned);
            filled.add(shares);
            bindGroup(layout, operations, groupIndex + 1, filled, stage, path);
            return;
        }
        List<String> items = layout.groups.get(groupIndex).choices();
        Map<String, Long> upper = capacities.get(stage.level - 1);
        while (index < items.size() && room(upper, stage.earlier, items.get(index)) == 0) index++;
        if (index == items.size()) return;
        String item = items.get(index);
        int next = index + 1;
        long other = 0;
        for (int i = next; i < items.size() && other < units; i++) other = saturatedAdd(other, room(upper, stage.earlier, items.get(i)));
        choices(Math.max(0, units - other), Math.min(units, room(upper, stage.earlier, item)), count -> {
            List<Share> allocated = shares;
            Stage updated = stage;
            if (count > 0) {
                allocated = new ArrayList<>(shares);
                allocated.add(new Share(item, count));
                updated = new Stage(stage.level, stage.due, change(stage.earlier, item, count), stage.recipeIndex);
            }
            distribute(layout, operations, groupIndex, assigned, updated, path, next, units - count, allocated);
        });
    }

    private void chooseSources(Map<String, Long> needs, Path path) {
        long pressure = 0;
        long protectedUnits = 0;
        for (Map.Entry<String, Long> entry : needs.entrySet()) {
            String item = entry.getKey();
            long units = entry.getValue();
            long usable = capacities.getFirst().getOrDefault(item, 0L);
            if (units > usable) return;
            pressure = Math.addExact(pressure, Math.multiplyExact(units, SupplySolver.unitPressure(usable)));
            if (request.preferKeeping().contains(item)) protectedUnits = Math.addExact(protectedUnits, units);
        }
        withdraw(needs, 0, Set.of(), new Score(protectedUnits, pressure, path.crafts), path.history);
    }

    private void withdraw(Map<String, Long> needs, int index, Set<String> visited, Score score, History history) {
        if (best != null) {
            if (score.compare(best.score(), request.policy()) >= 0) return;
            long operations = saturatedAdd(score.operations(), minimumVisitCost(needs, index, visited));
            if (new Score(score.protectedUnits(), score.pressure(), operations).compare(best.score(), request.policy()) >= 0) return;
        }
        if (needs.isEmpty()) {
            List<Action> actions = new ArrayList<>();
            for (History node = history; node != null; node = node.later) actions.addAll(node.actions);
            best = new Plan(actions, surplus(actions), score, request.mode());
            return;
        }
        String item = needs.keySet().iterator().next();
        long units = needs.get(item);
        List<Integer> supply = sources.get(item);
        if (index == supply.size()) return;
        int sourceIndex = supply.get(index);
        Source source = request.stock().get(sourceIndex);
        // 每箱只在首次访问计费。在任一最优的已访问箱集合内，同物品在较早货源尽量取足只会减少访问。
        if (units > laterSourceUnits[sourceIndex] + source.count()) return;
        if (units <= laterSourceUnits[sourceIndex] && source.visitCost() > 0 && !visited.contains(source.box())) {
            pending.push(() -> withdraw(needs, index + 1, visited, score, history));
        }
        int count = (int) Math.min(units, source.count());
        if (count == 0) return;
        Set<String> opened = visited;
        Score cost = score;
        if (!visited.contains(source.box())) {
            opened = new HashSet<>(visited);
            opened.add(source.box());
            cost = new Score(score.protectedUnits(), score.pressure(), Math.addExact(score.operations(), source.visitCost()));
        }
        Set<String> nextVisited = opened;
        Score nextScore = cost;
        History taken = new History(List.of(new Take(sourceIndex, item, count)), history);
        pending.push(() -> withdraw(change(needs, item, -count), count == units ? 0 : index + 1, nextVisited, nextScore, taken));
    }

    /** 每种材料先用满已访问箱和免费来源。余量至少需要若干个最大箱，按最低访问费计价只会低估。 */
    private long minimumVisitCost(Map<String, Long> needs, int firstIndex, Set<String> visited) {
        long separate = 0;
        long shared = 0;
        int start = firstIndex;
        for (Map.Entry<String, Long> need : needs.entrySet()) {
            long left = need.getValue();
            List<Integer> rows = sources.get(need.getKey());
            for (int i = start; i < rows.size() && left > 0; i++) {
                Source row = request.stock().get(rows.get(i));
                if (row.visitCost() == 0 || visited.contains(row.box())) left -= row.count();
            }
            start = 0;
            if (left <= 0) continue;
            BoxBound bound = boxBounds.get(need.getKey());
            if (bound.largest == 0) return Long.MAX_VALUE;
            long visits = Math.ceilDiv(left, bound.largest);
            long cost = visits > Long.MAX_VALUE / bound.cheapest ? Long.MAX_VALUE : visits * bound.cheapest;
            // 专用箱之间可以相加；混合箱可能一次满足多种材料，取最大值才能避免重复收费。
            if (bound.exclusive) separate = saturatedAdd(separate, cost);
            else shared = Math.max(shared, cost);
        }
        return saturatedAdd(separate, shared);
    }

    private Map<String, Long> surplus(List<Action> actions) {
        Map<String, Long> ledger = new HashMap<>();
        for (Action action : actions) {
            if (action instanceof Take take) ledger.merge(take.item(), (long) take.count(), Math::addExact);
            else if (action instanceof Make make) {
                for (String item : make.grid()) {
                    if (!item.isEmpty() && ledger.merge(item, -(long) make.operations(), Math::addExact) < 0)
                        throw new IllegalStateException("制作前原料不足: " + item);
                }
                ledger.merge(make.recipe().output(), (long) make.operations() * make.recipe().yield(), Math::addExact);
            }
        }
        for (Map.Entry<String, Long> goal : delivery.entrySet()) {
            if (ledger.merge(goal.getKey(), -goal.getValue(), Math::addExact) < 0) throw new IllegalStateException("未完成承诺的交付量");
        }
        ledger.values().removeIf(count -> count == 0);
        return ledger;
    }

    /** 区间可达整型数量级。栈上只挂下一个选择，用户取消时也无需展开剩余兄弟分支。 */
    private void choices(long min, long max, LongConsumer visit) {
        if (min > max) return;
        pending.push(new Runnable() {
            long next = max;
            @Override public void run() {
                long current = next--;
                if (current > min) pending.push(this);
                visit.accept(current);
            }
        });
    }
}
