package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 客户端线程与求解线程之间传递的纯 Java 快照和动作。 */
public final class SupplyModel {
    private SupplyModel() {}

    public enum Policy { SAVE_STOCK, FEWEST_OPERATIONS }
    public enum Mode { PREPARE, RAW_ONLY }
    public enum Status { BEST_IN_MODEL, FEASIBLE_LIMIT, NO_PLAN_IN_MODEL, UNKNOWN_LIMIT }

    /** 空列表表示空格，其他格位必须选择其中一种物品。 */
    public record Slot(List<String> choices) {
        public Slot {
            choices = choices.stream().distinct().sorted().toList();
        }
    }

    public record Recipe(String id, String type, String output, int yield, List<Slot> slots) {
        public Recipe {
            slots = List.copyOf(slots);
            if (yield < 1 || slots.size() != 9 || slots.stream().allMatch(slot -> slot.choices.isEmpty())) {
                throw new IllegalArgumentException("需要有效产量和九格布局: " + id);
            }
        }
    }

    public record Catalog(List<Recipe> recipes) {
        public Catalog {
            recipes = List.copyOf(recipes);
            Set<String> ids = new HashSet<>();
            for (Recipe recipe : recipes) {
                if (!ids.add(recipe.id)) throw new IllegalArgumentException("重复配方: " + recipe.id);
            }
        }
    }

    public record Source(String box, String item, int count, int visitCost) {
        public Source {
            if (count < 0 || visitCost < 0) throw new IllegalArgumentException("负库存或访问成本");
        }
    }

    public record Hint(String item, int count, String recipeType, List<Hint> children) {
        public Hint {
            children = List.copyOf(children);
            if (count < 1) throw new IllegalArgumentException("无效提示数量");
        }
        Hint child(String id) {
            return children.stream().filter(node -> node.item.equals(id)).findFirst().orElse(null);
        }
    }

    public record Goal(String item, int units, Hint hint) {
        public Goal {
            if (units <= 0 || hint != null && !hint.item.equals(item)) throw new IllegalArgumentException("无效目标");
        }
    }

    /** floors 是从期初来源中保留的库存，units 是需要额外交付的数量。 */
    public record Request(Catalog catalog, List<Source> stock, List<Goal> goals, Map<String, Integer> floors,
                          Set<String> preferKeeping, Set<String> stopAt, int depth, Policy policy, Mode mode) {
        public Request {
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(mode);
            stock = List.copyOf(stock);
            goals = List.copyOf(goals);
            floors = Map.copyOf(floors);
            preferKeeping = Set.copyOf(preferKeeping);
            stopAt = Set.copyOf(stopAt);
            if (depth < 0 || depth > 32 || floors.values().stream().anyMatch(amount -> amount < 0)) {
                throw new IllegalArgumentException("无效深度或库存下限");
            }
            Map<String, Integer> visits = new LinkedHashMap<>();
            for (Source source : stock) {
                Integer existing = visits.putIfAbsent(source.box, source.visitCost);
                if (existing != null && existing != source.visitCost) throw new IllegalArgumentException("同箱成本不一致");
            }
        }
    }

    public sealed interface Action permits Take, Make {}

    /** source 是本次快照中的货源索引。数量随方案传递，执行器可以按背包容量分段。 */
    public record Take(int source, String item, int count) implements Action {
        public Take(int source, String item) { this(source, item, 1); }
        public Take {
            if (count <= 0) throw new IllegalArgumentException("无效取料数量");
        }
    }

    /** 固定九格布局及制作次数。空格用空字符串表示。 */
    public record Make(Recipe recipe, List<String> grid, boolean followsHint, int operations) implements Action {
        public Make(Recipe recipe, List<String> grid, boolean followsHint) { this(recipe, grid, followsHint, 1); }
        public Make {
            grid = List.copyOf(grid);
            if (operations <= 0) throw new IllegalArgumentException("无效制作次数");
        }
    }

    public record Score(long protectedUnits, long pressure, long operations) {
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

    public record Plan(List<Action> actions, Map<String, Long> surplus, Score score, Mode mode) {
        public Plan {
            actions = List.copyOf(actions);
            surplus = Map.copyOf(surplus);
        }

        public List<Action> executable() {
            return mode == Mode.RAW_ONLY ? actions.stream().filter(Take.class::isInstance).toList() : actions;
        }

        public Map<String, Long> shoppingList() {
            Map<String, Long> list = new LinkedHashMap<>();
            for (Action action : actions) {
                if (action instanceof Take take) list.merge(take.item, (long) take.count, Math::addExact);
            }
            return Collections.unmodifiableMap(list);
        }
    }
    public record Result(Status status, Plan plan, long explored, List<List<String>> blockedExamples) {
        public Result {
            blockedExamples = blockedExamples.stream().map(List::copyOf).toList();
        }
    }
}
