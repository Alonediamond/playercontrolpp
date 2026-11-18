package com.alonediamond.playercontrolpp.feature.automaterial.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class SupplyModel {
    enum Policy { SAVE_STOCK, FEWEST_OPERATIONS }
    enum Mode { PREPARE, RAW_ONLY }
    enum Status { BEST_IN_MODEL, FEASIBLE_LIMIT, NO_PLAN_IN_MODEL, UNKNOWN_LIMIT }

    record Slot(List<String> choices) {
        Slot { choices = choices.stream().distinct().sorted().toList(); }
    }

    record Recipe(String id, String type, String output, int yield, List<Slot> slots) {
        Recipe {
            slots = List.copyOf(slots);
            if (yield < 1 || slots.size() != 9 || slots.stream().allMatch(slot -> slot.choices.isEmpty()))
                throw new IllegalArgumentException("需要有效产量和九格布局: " + id);
        }
    }

    static final class Catalog {
        private final Map<String, List<Recipe>> index = new LinkedHashMap<>();

        Catalog(List<Recipe> recipes) {
            Set<String> ids = new HashSet<>();
            for (Recipe recipe : recipes) {
                if (!ids.add(recipe.id)) throw new IllegalArgumentException("重复配方: " + recipe.id);
                index.computeIfAbsent(recipe.output, ignored -> new ArrayList<>()).add(recipe);
            }
            index.replaceAll((item, options) -> List.copyOf(options));
        }

        List<Recipe> forOutput(String item) { return index.getOrDefault(item, List.of()); }
    }

    record Source(String box, String item, int count, int visitCost) {
        Source {
            if (count < 0 || visitCost < 0) throw new IllegalArgumentException("负库存或访问成本");
        }
    }

    record Hint(String item, int count, String recipeType, List<Hint> children) {
        Hint {
            children = List.copyOf(children);
            if (count < 1) throw new IllegalArgumentException("无效提示数量");
        }
        Hint child(String id) {
            return children.stream().filter(node -> node.item.equals(id)).findFirst().orElse(null);
        }
    }

    record Goal(String item, int units, Hint hint) {
        Goal {
            if (units <= 0 || hint != null && !hint.item.equals(item)) throw new IllegalArgumentException("无效目标");
        }
    }

    record Request(Catalog catalog, List<Source> stock, List<Goal> goals, Map<String, Integer> floors,
                   Set<String> preferKeeping, Set<String> stopAt, int depth, Policy policy, Mode mode) {
        Request {
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(mode);
            stock = List.copyOf(stock);
            goals = List.copyOf(goals);
            floors = Map.copyOf(floors);
            preferKeeping = Set.copyOf(preferKeeping);
            stopAt = Set.copyOf(stopAt);
            if (depth < 0 || depth > 32 || floors.values().stream().anyMatch(amount -> amount < 0))
                throw new IllegalArgumentException("无效深度或库存下限");
            Map<String, Integer> visits = new LinkedHashMap<>();
            for (Source source : stock) {
                Integer existing = visits.putIfAbsent(source.box, source.visitCost);
                if (existing != null && existing != source.visitCost) throw new IllegalArgumentException("同箱成本不一致");
            }
        }
    }

    sealed interface Action permits Take, Make {}
    record Take(int source, String item) implements Action {}
    record Make(Recipe recipe, List<String> grid, boolean followsHint) implements Action {
        Make { grid = List.copyOf(grid); }
    }

    record Score(long protectedUnits, long pressure, long operations) {
        /** 比较整单而非某个格位。省库存依次比较软保护用量、库存消耗比例、操作成本；少操作则把操作成本放在首位。 */
        int compare(Score other, Policy policy) {
            if (policy == Policy.FEWEST_OPERATIONS) {
                int order = Long.compare(operations, other.operations);
                if (order != 0) return order;
            }
            int order = Long.compare(protectedUnits, other.protectedUnits);
            if (order == 0) order = Long.compare(pressure, other.pressure);
            return order != 0 ? order : Long.compare(operations, other.operations);
        }
    }

    record Plan(List<Action> actions, Map<String, Long> surplus, Score score, Mode mode) {
        Plan {
            actions = List.copyOf(actions);
            surplus = Map.copyOf(surplus);
        }
        List<Action> executable() {
            return mode == Mode.RAW_ONLY ? actions.stream().filter(Take.class::isInstance).toList() : actions;
        }
        Map<String, Long> shoppingList() {
            Map<String, Long> list = new LinkedHashMap<>();
            for (Action action : actions) {
                if (action instanceof Take take) list.merge(take.item, 1L, Long::sum);
            }
            return Collections.unmodifiableMap(list);
        }
    }
    record Result(Status status, Plan plan, long explored, List<List<String>> blockedExamples) {}
}
