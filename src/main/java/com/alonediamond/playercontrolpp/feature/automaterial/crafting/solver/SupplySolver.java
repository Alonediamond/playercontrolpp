package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Hint;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Mode;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Policy;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Request;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Score;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Slot;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Status;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;

/**
 * 纯 Java 规划器，由工作线程调用。先求一个格位或一个交付单位，再确认对应制作和交付。
 * 只沿当前分支记录模拟库存，无需预先生成配方的每种格位组合。
 */
public final class SupplySolver {
    private static final long PRESSURE_SCALE = 1_000_000L;
    static long unitPressure(long available) { return Math.ceilDiv(PRESSURE_SCALE, available); }
    private static final int MAX_BLOCKED_EXAMPLES = 8;
    private static final int MAX_ACTIVE_FRAMES = 768;
    private static final int MAX_DELIVERY_STATES = 4096;
    private static final StockEstimate FREE = new StockEstimate(0, 0);
    private static final StockEstimate UNKNOWN = new StockEstimate(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    private sealed interface Task permits Demand, Finish, Deliver {}
    private record Demand(List<String> choices, Set<String> ancestors, Hint parentHint, Hint rootHint) implements Task {
        Hint hintFor(String item) { return parentHint == null ? rootHint : parentHint.child(item); }
    }
    private record Finish(Recipe recipe, Hint hint) implements Task {}
    private record Deliver(int goalIndex, int remaining) implements Task {}
    private record DeliveryState(Deliver progress, List<Integer> stock, Map<String, Long> surplus) {}
    private record Prefix(Score score, int deviations) {}

    private record StockEstimate(double protectedUnits, double pressure) implements Comparable<StockEstimate> {
        @Override
        public int compareTo(StockEstimate other) {
            int order = Double.compare(protectedUnits, other.protectedUnits);
            return order != 0 ? order : Double.compare(pressure, other.pressure);
        }

        StockEstimate min(StockEstimate other) {
            return compareTo(other) <= 0 ? this : other;
        }
    }

    /** 一条尚未承诺的尝试：stock 按货源记剩余实物，available 按物品扣除硬下限，surplus 是本分支已制出的余料。 */
    private static final class Branch {
        final Deque<Task> todo;
        final List<String> bindings;
        final int[] stock;
        final Map<String, Long> available;
        final Map<String, Long> surplus;
        final Set<String> visited;
        final List<Action> actions;
        Score score = new Score(0, 0, 0);
        int deviations;

        Branch(Request request, Map<String, Long> initial) {
            todo = new ArrayDeque<>();
            bindings = new ArrayList<>();
            stock = request.stock().stream().mapToInt(Source::count).toArray();
            available = new HashMap<>(initial);
            surplus = new HashMap<>();
            visited = new HashSet<>();
            actions = new ArrayList<>();
        }

        // 兄弟分支不能共享可变账本；试用白桦失败时，橡木分支应从同一份父库存出发。
        Branch(Branch parent) {
            todo = new ArrayDeque<>(parent.todo);
            bindings = new ArrayList<>(parent.bindings);
            stock = parent.stock.clone();
            available = new HashMap<>(parent.available);
            surplus = new HashMap<>(parent.surplus);
            visited = new HashSet<>(parent.visited);
            actions = new ArrayList<>(parent.actions);
            score = parent.score;
            deviations = parent.deviations;
        }
    }

    private final Request request;
    private final long started = System.nanoTime();
    private final long maxNanos;
    private final Map<String, Long> initial = new HashMap<>();
    private final Map<String, List<Integer>> sources = new HashMap<>();
    private final Set<String> reachable = new HashSet<>();
    private final Map<String, List<Recipe>> reachableRecipes = new HashMap<>();
    private final Set<String> fixedGoalInputs = new HashSet<>();
    private final Map<String, StockEstimate> recipeEstimates = new HashMap<>();
    private final Set<List<String>> blocked = new LinkedHashSet<>();
    private final Map<DeliveryState, Prefix> deliveries = new HashMap<>();
    private Plan best;
    private int bestDeviations;

    private SupplySolver(Request request, long maxNanos) {
        this.request = request;
        this.maxNanos = maxNanos;
    }

    private boolean indexStock() {
        for (int index = 0; index < request.stock().size(); index++) {
            if (timedOut()) return false;
            Source source = request.stock().get(index);
            if (source.count() == 0) continue;
            initial.merge(source.item(), (long) source.count(), Math::addExact);
            sources.computeIfAbsent(source.item(), ignored -> new ArrayList<>()).add(index);
        }
        for (Map.Entry<String, Integer> floor : request.floors().entrySet()) {
            if (timedOut()) return false;
            initial.merge(floor.getKey(), -(long) floor.getValue(), Math::addExact);
        }
        return !timedOut();
    }

    /** 忽略数量与祖先限制，只排除连期初种子都没有的配方分支。 */
    private boolean indexReachability() {
        for (Map.Entry<String, Long> stock : initial.entrySet()) {
            if (stock.getValue() > 0) reachable.add(stock.getKey());
        }
        List<Recipe> recipes = request.catalog().recipes();
        boolean grew;
        do {
            grew = false;
            for (Recipe recipe : recipes) {
                if (timedOut()) return false;
                if (!reachable.contains(recipe.output()) && !request.stopAt().contains(recipe.output())
                        && hasReachableInputs(recipe)) {
                    grew |= reachable.add(recipe.output());
                }
            }
        } while (grew);
        for (Recipe recipe : recipes) {
            if (timedOut()) return false;
            if (hasReachableInputs(recipe)) {
                reachableRecipes.computeIfAbsent(recipe.output(), ignored -> new ArrayList<>()).add(recipe);
            }
        }
        for (Goal goal : request.goals()) {
            for (Recipe recipe : reachableRecipes.getOrDefault(goal.item(), List.of())) {
                if (timedOut()) return false;
                for (Slot slot : recipe.slots()) {
                    if (slot.choices().size() == 1) fixedGoalInputs.add(slot.choices().getFirst());
                }
            }
        }
        return !timedOut();
    }

    private boolean hasReachableInputs(Recipe recipe) {
        for (Slot slot : recipe.slots()) {
            if (!slot.choices().isEmpty() && slot.choices().stream().noneMatch(reachable::contains)) return false;
        }
        return true;
    }

    public static Result solve(Request request, long maxNodes) {
        return solve(request, maxNodes, Long.MAX_VALUE);
    }

    /** 时间或节点预算耗尽时，仅返回已经完整交付的候选。调用方可中断工作线程取消。 */
    public static Result solve(Request request, long maxNodes, long maxNanos) {
        if (maxNodes < 1 || maxNanos < 0) throw new IllegalArgumentException("无效搜索预算");
        return new SupplySolver(request, maxNanos).search(maxNodes);
    }

    private boolean timedOut() {
        return Thread.currentThread().isInterrupted() || System.nanoTime() - started >= maxNanos;
    }

    private Result search(long maxNodes) {
        if (!indexStock()) return result(Status.UNKNOWN_LIMIT, 0);
        for (Map.Entry<String, Long> entry : initial.entrySet()) {
            if (timedOut()) return result(Status.UNKNOWN_LIMIT, 0);
            if (entry.getValue() < 0) {
                return new Result(Status.NO_PLAN_IN_MODEL, null, 0, List.of(List.of(entry.getKey())));
            }
        }
        if (!indexReachability()) return result(Status.UNKNOWN_LIMIT, 0);
        Branch start = new Branch(request, initial);
        scheduleGoal(start, 0, 0);
        Deque<Iterator<Supplier<Branch>>> frontier = new ArrayDeque<>();
        frontier.push(singleBranch(start));
        long explored = 0;
        while (!frontier.isEmpty()) {
            if (explored >= maxNodes || frontier.size() > MAX_ACTIVE_FRAMES || timedOut()) {
                return limited(explored);
            }
            Iterator<Supplier<Branch>> alternatives = frontier.peek();
            boolean hasBranch = alternatives.hasNext();
            if (timedOut()) return limited(explored);
            if (!hasBranch) {
                frontier.pop();
                continue;
            }
            Branch branch = alternatives.next().get();
            explored++;
            if (!competitive(branch)) continue;
            Task task = branch.todo.poll();
            if (task == null) {
                // 推荐的结果断点：此时所有目标都已交付。首个到这里的不是最终答案，观察后续 best 的替换。
                best = new Plan(branch.actions, branch.surplus, branch.score, request.mode());
                bestDeviations = branch.deviations;
            } else if (task instanceof Deliver deliver) {
                branch.bindings.removeLast();
                if (dominatedAtDelivery(branch, deliver)) continue;
                scheduleGoal(branch, deliver.goalIndex, deliver.remaining);
                frontier.push(singleBranch(branch));
            } else if (task instanceof Finish finish) {
                assemble(branch, finish);
                frontier.push(singleBranch(branch));
            } else if (task instanceof Demand demand) {
                frontier.push(expand(branch, demand).iterator());
            }
        }
        return result(best == null ? Status.NO_PLAN_IN_MODEL : Status.BEST_IN_MODEL, explored);
    }

    private static Iterator<Supplier<Branch>> singleBranch(Branch branch) {
        return List.<Supplier<Branch>>of(() -> branch).iterator();
    }

    private boolean dominatedAtDelivery(Branch branch, Deliver progress) {
        // 仅在一个顶层单位交付完毕时归并：这时没有悬挂格位或父配方。货源余量决定已访问的箱子和各物品可取额度。
        DeliveryState state = new DeliveryState(progress, Arrays.stream(branch.stock).boxed().toList(), Map.copyOf(branch.surplus));
        Prefix prior = deliveries.get(state);
        if (prior != null) {
            int order = prior.score.compare(branch.score, request.policy());
            if (order < 0 || order == 0 && prior.deviations <= branch.deviations) return true;
        }
        if (prior != null || deliveries.size() < MAX_DELIVERY_STATES) {
            deliveries.put(state, new Prefix(branch.score, branch.deviations));
        }
        return false;
    }

    private Result result(Status status, long explored) {
        return new Result(status, best, explored, best == null ? List.copyOf(blocked) : List.of());
    }

    private Result limited(long explored) {
        return result(best == null ? Status.UNKNOWN_LIMIT : Status.FEASIBLE_LIMIT, explored);
    }

    /** 现有模型只累加取货和制作成本；半成品方案的成本已不可能胜出时，其后代也不用再试。 */
    private boolean competitive(Branch branch) {
        if (best == null) return true;
        int order = branch.score.compare(best.score(), request.policy());
        return order < 0 || order == 0 && branch.deviations < bestDeviations;
    }

    private void scheduleGoal(Branch branch, int index, int remaining) {
        if (remaining == 0) {
            if (index == request.goals().size()) return;
            remaining = request.goals().get(index).units();
        }
        Goal goal = request.goals().get(index);
        branch.todo.push(new Deliver(remaining == 1 ? index + 1 : index, remaining - 1));
        branch.todo.push(new Demand(List.of(goal.item()), Set.of(), null, goal.hint()));
    }

    private Stream<Supplier<Branch>> expand(Branch parent, Demand demand) {
        List<String> choices = demand.choices.stream().filter(reachable::contains).toList();
        if (choices.size() > 1) {
            // 通用格优先尝试不会与成品的固定格争料的选项。保留其余选项供回溯。
            Comparator<String> order = Comparator.comparing(fixedGoalInputs::contains);
            if (rankStockAlternatives()) {
                Map<String, StockEstimate> estimates = new HashMap<>();
                order = order.thenComparing(item -> estimates.computeIfAbsent(item, key -> estimateChoice(parent, demand, key)));
            }
            choices = choices.stream().sorted(order.thenComparing(item -> demand.hintFor(item) == null)).toList();
        }
        if (blocked.size() < MAX_BLOCKED_EXAMPLES && choices.stream().takeWhile(item -> !timedOut())
                .noneMatch(item -> potentiallyAvailable(parent, item, demand)) && !timedOut()) {
            blocked.add(demand.choices);
        }
        // 流迭代器可能提前枚举同一物品的多个来源，选中候选时才复制账本。
        return choices.stream().takeWhile(item -> !timedOut()).flatMap(item -> alternatives(parent, demand, item));
    }

    private boolean canExpand(String item, Demand demand) {
        return demand.ancestors.size() < request.depth() && !demand.ancestors.contains(item)
                && !request.stopAt().contains(item);
    }

    private boolean potentiallyAvailable(Branch branch, String item, Demand demand) {
        if (request.mode() == Mode.RAW_ONLY && demand.ancestors.contains(item)) return false;
        return branch.surplus.getOrDefault(item, 0L) > 0 || branch.available.getOrDefault(item, 0L) > 0
                || !recipesFor(item, demand, demand.hintFor(item)).isEmpty();
    }

    private List<Recipe> recipesFor(String item, Demand demand, Hint hint) {
        // LitematList 的空叶还可能来自该次展开无路可走。成品求解的硬终点由快照的 stopAt 决定。
        boolean hintedStop = request.mode() == Mode.RAW_ONLY && hint != null && hint.children().isEmpty();
        if (hintedStop || !canExpand(item, demand)) return List.of();
        return reachableRecipes.getOrDefault(item, List.of());
    }

    private boolean rankStockAlternatives() {
        return request.policy() == Policy.SAVE_STOCK && request.mode() == Mode.PREPARE;
    }

    private StockEstimate estimateChoice(Branch branch, Demand demand, String item) {
        if (branch.surplus.getOrDefault(item, 0L) > 0) return FREE;
        StockEstimate estimate = directEstimate(item, branch.available.getOrDefault(item, 0L));
        for (Recipe recipe : recipesFor(item, demand, demand.hintFor(item))) {
            if (timedOut()) break;
            estimate = estimate.min(estimateRecipe(recipe));
        }
        return estimate;
    }

    private StockEstimate directEstimate(String item, long available) {
        if (available <= 0) return UNKNOWN;
        return new StockEstimate(request.preferKeeping().contains(item) ? 1 : 0,
                unitPressure(initial.get(item)));
    }

    /** 按期初散装估算一次制作，并按产量摊销。这个数只决定探索顺序，深层和暂不可估分支仍可参与搜索。 */
    private StockEstimate estimateRecipe(Recipe recipe) {
        return recipeEstimates.computeIfAbsent(recipe.id(), ignored -> {
            double protectedUnits = 0;
            double pressure = 0;
            for (Slot slot : recipe.slots()) {
                if (timedOut()) return UNKNOWN;
                if (slot.choices().isEmpty()) continue;
                StockEstimate best = UNKNOWN;
                for (String choice : slot.choices()) {
                    if (timedOut()) return UNKNOWN;
                    best = best.min(directEstimate(choice, initial.getOrDefault(choice, 0L)));
                }
                if (best == UNKNOWN) return UNKNOWN;
                protectedUnits += best.protectedUnits();
                pressure += best.pressure();
            }
            return new StockEstimate(protectedUnits / recipe.yield(), pressure / recipe.yield());
        });
    }

    private Stream<Supplier<Branch>> alternatives(Branch parent, Demand demand, String item) {
        if (request.mode() == Mode.RAW_ONLY && demand.ancestors.contains(item)) return Stream.empty();
        Hint hint = demand.hintFor(item);
        List<Recipe> recipes = recipesFor(item, demand, hint);
        Comparator<Recipe> order = Comparator.comparing(recipe -> !typeMatches(recipe, hint));
        boolean produceFirst = request.policy() == Policy.SAVE_STOCK && request.preferKeeping().contains(item);
        if (rankStockAlternatives() && !recipes.isEmpty()) {
            StockEstimate cheapest = recipes.stream().map(this::estimateRecipe).min(StockEstimate::compareTo).orElse(UNKNOWN);
            StockEstimate direct = directEstimate(item, parent.available.getOrDefault(item, 0L));
            produceFirst = cheapest.compareTo(direct) < 0;
            order = Comparator.comparing(this::estimateRecipe).thenComparing(order);
        }
        Stream<Recipe> ordered = recipes.size() > 1 ? recipes.stream().sorted(order) : recipes.stream();
        Stream<Supplier<Branch>> produce = ordered.takeWhile(recipe -> !timedOut())
                .map(recipe -> () -> openRecipe(parent, demand, recipe, hint));
        boolean hasSurplus = parent.surplus.getOrDefault(item, 0L) > 0;
        // 普通备货允许在任一出现点拿现货：有相同余料时可把拿现货延后，不会更贵。
        // RAW_ONLY 只允许叶子拿现货，不能把这个推论用于它的非叶子格位。
        boolean allowTake = request.mode() == Mode.RAW_ONLY ? recipes.isEmpty() : !hasSurplus;
        Stream<Supplier<Branch>> take = !allowTake || parent.available.getOrDefault(item, 0L) <= 0 ? Stream.empty()
                : sources.getOrDefault(item, List.of()).stream().takeWhile(index -> !timedOut())
                        .filter(index -> parent.stock[index] > 0).map(index -> () -> withdraw(parent, index));
        // concat 只决定先试哪一边；有木板现货时，「从原木制板」并没有被排除。
        Stream<Supplier<Branch>> supply = produceFirst
                ? Stream.concat(produce, take) : Stream.concat(take, produce);
        if (!hasSurplus) return supply;
        // 优先试免费余料，但不能直接返回它：后面的深度受限格位也许只能用现成品。
        return Stream.concat(Stream.of(() -> useSurplus(parent, item)), supply);
    }

    private Branch useSurplus(Branch parent, String item) {
        Branch child = new Branch(parent);
        if (child.surplus.merge(item, -1L, Long::sum) == 0) child.surplus.remove(item);
        child.bindings.add(item);
        return child;
    }

    private Branch withdraw(Branch parent, int index) {
        Source source = request.stock().get(index);
        Branch branch = new Branch(parent);
        branch.stock[index]--;
        branch.available.merge(source.item(), -1L, Long::sum);
        branch.bindings.add(source.item());
        branch.actions.add(new Take(index, source.item()));
        // 断点看 source.item() 和 stockPressure：同取一根，消耗 1/24 白桦比 1/8 橡木便宜。
        // 分母是期初可动用总量，不是取走一个后重新计算；稀有只代表分高，不是禁用。
        long stockPressure = unitPressure(initial.get(source.item()));
        branch.score = new Score(branch.score.protectedUnits() + (request.preferKeeping().contains(source.item()) ? 1 : 0),
                Math.addExact(branch.score.pressure(), stockPressure),
                branch.score.operations() + (branch.visited.add(source.box()) ? source.visitCost() : 0));
        return branch;
    }

    private Branch openRecipe(Branch parent, Demand demand, Recipe recipe, Hint hint) {
        Branch branch = new Branch(parent);
        Set<String> path = new HashSet<>(demand.ancestors);
        path.add(recipe.output());
        Set<String> ancestors = Set.copyOf(path);
        // 后进先出：先挂起完成动作，再倒序压入格位，最后能按真实图案顺序重建九格。
        branch.todo.push(new Finish(recipe, hint));
        for (Slot slot : recipe.slots().reversed()) {
            if (!slot.choices().isEmpty()) branch.todo.push(new Demand(slot.choices(), ancestors, hint, null));
        }
        branch.score = new Score(branch.score.protectedUnits(), branch.score.pressure(), branch.score.operations() + 1);
        return branch;
    }

    private static boolean typeMatches(Recipe recipe, Hint hint) {
        return hint != null && recipe.type().equals(hint.recipeType());
    }

    private void assemble(Branch branch, Finish finish) {
        Recipe recipe = finish.recipe;
        int occupied = (int) recipe.slots().stream().filter(slot -> !slot.choices().isEmpty()).count();
        List<String> allocated = branch.bindings.subList(branch.bindings.size() - occupied, branch.bindings.size());
        Iterator<String> items = allocated.iterator();
        List<String> grid = recipe.slots().stream().map(slot -> slot.choices().isEmpty() ? "" : items.next()).toList();
        boolean follows = matchesHint(recipe, finish.hint, allocated);
        if (!follows && finish.hint != null) branch.deviations++;
        // 只归约这次制作的格位；一份产物交回上层，余下的从此刻起才可用。
        allocated.clear();
        branch.bindings.add(recipe.output());
        if (recipe.yield() > 1) branch.surplus.merge(recipe.output(), recipe.yield() - 1L, Math::addExact);
        branch.actions.add(new Make(recipe, grid, follows));
    }

    private static boolean matchesHint(Recipe recipe, Hint hint, List<String> allocated) {
        if (!typeMatches(recipe, hint)) return false;
        long repetitions = Math.ceilDiv((long) hint.count(), recipe.yield());
        Map<String, Long> expected = new HashMap<>();
        Map<String, Long> actual = new HashMap<>();
        for (Hint child : hint.children()) expected.merge(child.item(), (long) child.count(), Math::addExact);
        for (String item : allocated) actual.merge(item, repetitions, Math::addExact);
        return expected.equals(actual);
    }
}
