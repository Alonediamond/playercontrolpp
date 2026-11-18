package com.alonediamond.playercontrolpp.feature.automaterial.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Hint;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Mode;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Policy;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Request;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Score;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Slot;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Status;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Take;

final class SupplySolver {
    private static final long PRESSURE_SCALE = 1_000_000L;
    private static final int MAX_BLOCKED_EXAMPLES = 8;
    private static final int MAX_ACTIVE_FRAMES = 768;

    private sealed interface Task permits Demand, Finish, Deliver {}
    private record Demand(List<String> choices, Set<String> ancestors, Hint parentHint, Hint rootHint) implements Task {
        Hint hintFor(String item) { return parentHint == null ? rootHint : parentHint.child(item); }
    }
    private record Finish(Recipe recipe, Hint hint) implements Task {}
    private record Deliver(int goalIndex, int remaining) implements Task {}

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
    private final Map<String, Long> initial = new HashMap<>();
    private final Map<String, List<Integer>> sources = new HashMap<>();
    private final Set<List<String>> blocked = new LinkedHashSet<>();
    private Plan best;
    private int bestDeviations;

    private SupplySolver(Request request) {
        this.request = request;
        for (int index = 0; index < request.stock().size(); index++) {
            Source source = request.stock().get(index);
            initial.merge(source.item(), (long) source.count(), Math::addExact);
            sources.computeIfAbsent(source.item(), ignored -> new ArrayList<>()).add(index);
        }
        request.floors().forEach((item, floor) -> initial.merge(item, -(long) floor, Math::addExact));
    }

    static Result solve(Request request, long maxNodes) {
        return solve(request, maxNodes, Long.MAX_VALUE);
    }

    static Result solve(Request request, long maxNodes, long maxNanos) {
        if (maxNodes < 1 || maxNanos < 0) throw new IllegalArgumentException("无效搜索预算");
        long started = System.nanoTime();
        return new SupplySolver(request).search(maxNodes, started, maxNanos);
    }

    private Result search(long maxNodes, long started, long maxNanos) {
        Branch start = new Branch(request, initial);
        for (Map.Entry<String, Long> entry : initial.entrySet()) {
            if (entry.getValue() < 0)
                return new Result(Status.NO_PLAN_IN_MODEL, null, 0, List.of(List.of(entry.getKey())));
        }
        scheduleGoal(start, 0, 0);
        Deque<Iterator<Branch>> frontier = new ArrayDeque<>();
        frontier.push(List.of(start).iterator());
        long explored = 0;
        while (!frontier.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || explored >= maxNodes || frontier.size() > MAX_ACTIVE_FRAMES
                    || System.nanoTime() - started >= maxNanos) {
                return result(best == null ? Status.UNKNOWN_LIMIT : Status.FEASIBLE_LIMIT, explored);
            }
            Iterator<Branch> alternatives = frontier.peek();
            if (!alternatives.hasNext()) {
                frontier.pop();
                continue;
            }
            Branch branch = alternatives.next();
            explored++;
            if (!competitive(branch)) continue;
            Task task = branch.todo.poll();
            if (task == null) {
                best = new Plan(branch.actions, branch.surplus, branch.score, request.mode());
                bestDeviations = branch.deviations;
            } else if (task instanceof Deliver deliver) {
                branch.bindings.removeLast();
                scheduleGoal(branch, deliver.goalIndex, deliver.remaining);
                frontier.push(List.of(branch).iterator());
            } else if (task instanceof Finish finish) {
                assemble(branch, finish);
                frontier.push(List.of(branch).iterator());
            } else if (task instanceof Demand demand) {
                frontier.push(expand(branch, demand).iterator());
            }
        }
        return result(best == null ? Status.NO_PLAN_IN_MODEL : Status.BEST_IN_MODEL, explored);
    }

    private Result result(Status status, long explored) {
        return new Result(status, best, explored, best == null ? List.copyOf(blocked) : List.of());
    }

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

    private Stream<Branch> expand(Branch parent, Demand demand) {
        List<String> choices = demand.choices;
        if (choices.size() > 1 && demand.parentHint != null)
            choices = choices.stream().sorted(Comparator.comparing(item -> demand.hintFor(item) == null)).toList();
        if (blocked.size() < MAX_BLOCKED_EXAMPLES && choices.stream().noneMatch(item -> potentiallyAvailable(parent, item, demand)))
            blocked.add(choices);
        return choices.stream().flatMap(item -> alternatives(parent, demand, item));
    }

    private boolean canExpand(String item, Demand demand) {
        return demand.ancestors.size() < request.depth() && !demand.ancestors.contains(item)
                && !request.stopAt().contains(item);
    }

    private boolean potentiallyAvailable(Branch branch, String item, Demand demand) {
        if (request.mode() == Mode.RAW_ONLY && demand.ancestors.contains(item)) return false;
        Hint hint = demand.hintFor(item);
        return branch.surplus.getOrDefault(item, 0L) > 0 || branch.available.getOrDefault(item, 0L) > 0
                || (hint == null || !hint.children().isEmpty()) && canExpand(item, demand)
                    && !request.catalog().forOutput(item).isEmpty();
    }

    private Stream<Branch> alternatives(Branch parent, Demand demand, String item) {
        if (request.mode() == Mode.RAW_ONLY && demand.ancestors.contains(item)) return Stream.empty();
        if (parent.surplus.getOrDefault(item, 0L) > 0) {
            Branch child = new Branch(parent);
            if (child.surplus.merge(item, -1L, Long::sum) == 0) child.surplus.remove(item);
            child.bindings.add(item);
            return Stream.of(child);
        }
        Hint hint = demand.hintFor(item);
        boolean hintedStop = hint != null && hint.children().isEmpty();
        List<Recipe> recipes = !hintedStop && canExpand(item, demand) ? request.catalog().forOutput(item) : List.of();
        Stream<Recipe> ordered = recipes.stream();
        if (hint != null) ordered = ordered.sorted(Comparator.comparing(recipe -> !typeMatches(recipe, hint)));
        Stream<Branch> produce = ordered.map(recipe -> openRecipe(parent, demand, recipe, hint));
        if (request.mode() == Mode.RAW_ONLY && !recipes.isEmpty()) return produce;
        Stream<Branch> take = parent.available.getOrDefault(item, 0L) <= 0 ? Stream.empty()
                : sources.getOrDefault(item, List.of()).stream().filter(index -> parent.stock[index] > 0)
                        .map(index -> withdraw(parent, index));
        return request.policy() == Policy.SAVE_STOCK && request.preferKeeping().contains(item)
                ? Stream.concat(produce, take) : Stream.concat(take, produce);
    }

    private Branch withdraw(Branch parent, int index) {
        Source source = request.stock().get(index);
        Branch branch = new Branch(parent);
        branch.stock[index]--;
        branch.available.merge(source.item(), -1L, Long::sum);
        branch.bindings.add(source.item());
        branch.actions.add(new Take(index, source.item()));
        long stockPressure = Math.ceilDiv(PRESSURE_SCALE, initial.get(source.item()));
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
