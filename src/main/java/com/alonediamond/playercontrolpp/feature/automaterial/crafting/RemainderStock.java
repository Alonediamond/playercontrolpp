package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.config.Configs.BaritoneSettings;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** 整单转为逐项备货时保留同一批硬下限。后续挖到或制出的新增物品仍可使用。 */
public final class RemainderStock {
    private record Floor(int carried, int warehouse) {}
    private final Map<Item, Floor> floors = new HashMap<>();
    private final Map<Item, Long> allowances = new HashMap<>();
    private final List<String> settings;

    RemainderStock(Map<Item, Integer> requested, ToIntFunction<Item> plain, ToLongFunction<Item> cached) {
        this(requested, plain, cached, List.of());
    }

    private RemainderStock(Map<Item, Integer> requested, ToIntFunction<Item> plain, ToLongFunction<Item> cached,
                           List<String> settings) {
        this.settings = settings;
        requested.forEach((item, amount) -> {
            if (amount <= 0) return;
            int carried = Math.min(amount, plain.applyAsInt(item));
            int warehouse = amount - carried;
            floors.put(item, new Floor(carried, warehouse));
            allowances.put(item, Math.max(0L, cached.applyAsLong(item) - warehouse));
        });
    }

    static RemainderStock capture(ToIntFunction<Item> plain, ToLongFunction<Item> cached) {
        Map<Item, Integer> floors = new HashMap<>();
        List<String> settings = List.copyOf(BaritoneSettings.SOLVER_STOCK_FLOORS.getStrings());
        WholeOrderStock.parseFloors(settings).forEach((id, floor) ->
                floors.put(RegistryCompat.item(Identifier.parse(id)), floor));
        return new RemainderStock(floors, plain, cached, settings);
    }

    public static RemainderStock withoutSnapshot(GatherContext ctx) {
        return capture(item -> ItemUtil.countPlainLoose(ctx.client.player, item), item -> 0L);
    }

    public boolean protects(Item item) { return floors.containsKey(item); }
    public boolean hasFloors() { return !floors.isEmpty(); }
    public boolean settingsCurrent() { return settings.equals(BaritoneSettings.SOLVER_STOCK_FLOORS.getStrings()); }

    public int carriedFloor(Item item) {
        Floor floor = floors.get(item);
        return floor == null ? 0 : floor.carried();
    }

    /** 缓存额度跨箱共用；快照失效或用尽时，当前打开的箱子自身也可以证明有多少富余。 */
    public int canWithdraw(Item item, int inOpenBox) {
        Floor floor = floors.get(item);
        if (floor == null) return Integer.MAX_VALUE;
        long safe = Math.max(allowances.get(item), visibleSurplus(item, inOpenBox));
        return (int) Math.min(Integer.MAX_VALUE, safe);
    }

    int visibleSurplus(Item item, int inOpenBox) {
        return Math.max(0, inOpenBox - floors.get(item).warehouse());
    }

    public void spend(Item item, int attempted) {
        allowances.computeIfPresent(item, (key, remaining) -> Math.max(0L, remaining - attempted));
    }
}
