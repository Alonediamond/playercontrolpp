package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 背包放不下整单时沿已证明的生产依赖分段送料。只拆数量，不重新选货源、配方或格位。 */
public final class SupplySchedule {
    private static final class Lot {
        final Action action;
        final List<Input> inputs = new ArrayList<>();
        long requested;
        Lot(Action action) { this.action = action; }
    }

    private record Portion(Lot lot, long units) {}
    private record Input(int perCraft, List<Portion> supplies) {}

    private final Deque<Portion> remaining = new ArrayDeque<>();

    public SupplySchedule(Plan plan) {
        Map<String, Deque<Portion>> available = new LinkedHashMap<>();
        for (Action action : plan.actions()) {
            Lot lot = new Lot(action);
            String item;
            long output;
            if (action instanceof Take take) {
                item = take.item();
                output = take.count();
            } else {
                Make make = (Make) action;
                item = make.recipe().output();
                output = (long) make.operations() * make.recipe().yield();
                Map<String, Integer> inputs = new LinkedHashMap<>();
                for (String id : make.grid()) if (!id.isEmpty()) inputs.merge(id, 1, Integer::sum);
                inputs.forEach((id, perCraft) -> lot.inputs.add(new Input(perCraft,
                        claim(available.get(id), (long) perCraft * make.operations()))));
            }
            available.computeIfAbsent(item, key -> new ArrayDeque<>()).addLast(new Portion(lot, output));
        }
        // 终端包括清单交付和不可避免的余料。优先制作下游成品，避免先为余料囤原料。
        available.values().forEach(queue -> queue.forEach(remaining::addFirst));
    }

    private static List<Portion> claim(Deque<Portion> sources, long need) {
        List<Portion> bound = new ArrayList<>();
        while (need > 0) {
            Portion first = sources.removeFirst();
            long count = Math.min(first.units, need);
            bound.add(new Portion(first.lot, count));
            if (count < first.units) sources.addFirst(new Portion(first.lot, first.units - count));
            need -= count;
        }
        return bound;
    }

    public boolean isEmpty() { return remaining.isEmpty(); }

    /** 试排一段，不修改进度。容量检查失败可缩小数量重新试排。 */
    public final class Slice {
        private final List<Action> actions = new ArrayList<>();
        private final Map<Lot, Long> requested = new HashMap<>();
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private final Portion terminal;
        private final long count;

        private Slice(int limit) {
            terminal = remaining.getFirst();
            count = Math.min(limit, terminal.units);
            tasks.push(() -> require(terminal.lot, count));
            while (!tasks.isEmpty()) tasks.pop().run();
        }

        private void require(Lot lot, long units) {
            long before = requested.getOrDefault(lot, lot.requested);
            long after = Math.addExact(before, units);
            requested.put(lot, after);
            if (lot.action instanceof Take take) {
                actions.add(new Take(take.source(), take.item(), Math.toIntExact(units)));
                return;
            }
            Make make = (Make) lot.action;
            long start = Math.ceilDiv(before, make.recipe().yield());
            long end = Math.ceilDiv(after, make.recipe().yield());
            if (start == end) return;
            tasks.push(() -> actions.add(new Make(make.recipe(), make.grid(), make.followsHint(), Math.toIntExact(end - start))));
            for (Input input : lot.inputs) {
                long offset = 0;
                for (Portion supply : input.supplies) {
                    long next = offset + supply.units;
                    long overlap = Math.min(next, end * input.perCraft) - Math.max(offset, start * input.perCraft);
                    if (overlap > 0) tasks.push(() -> require(supply.lot, overlap));
                    offset = next;
                    if (offset >= end * input.perCraft) break;
                }
            }
        }

        public List<Action> actions() { return List.copyOf(actions); }

        public void commit() {
            if (remaining.getFirst() != terminal) throw new IllegalStateException("分段计划已经推进");
            requested.forEach((lot, used) -> lot.requested = used);
            remaining.removeFirst();
            if (count < terminal.units) remaining.addFirst(new Portion(terminal.lot, terminal.units - count));
        }
    }

    public Slice preview(int limit) {
        if (limit < 1) throw new IllegalArgumentException("无效分段数量");
        return new Slice(limit);
    }
}
