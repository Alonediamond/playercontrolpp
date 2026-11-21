package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration.LooseStockScan;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration.Stock;
import com.alonediamond.playercontrolpp.inventory.ContainerMenus;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** 只在客户端线程遍历 ChestTracker 的实时迭代器，输出不携带 Memory 或 ItemStack。 */
public final class CachedLooseStockScan<E> implements LooseStockScan {
    private static final long TICK_BUDGET_NANOS = 1_000_000L;
    private static final int MAX_STEPS_PER_TICK = 128;

    public record Storage(BlockPos position, Block block, List<BlockPos> connected, Iterable<ItemStack> items) {}

    private final BlockPos origin;
    private final long rangeSquared;
    private final Set<Item> items;
    private final Iterator<E> memories;
    private final Function<E, Storage> readStorage;
    private final BooleanSupplier isCurrent;
    private final Set<BlockPos> seenPositions = new HashSet<>();
    private final Map<Item, Integer> currentStock = new LinkedHashMap<>();
    private final List<Stock> stock = new ArrayList<>();
    private Iterator<ItemStack> stacks = List.<ItemStack>of().iterator();
    private BlockPos source;
    private boolean valid = true;
    private List<Stock> finished;

    public CachedLooseStockScan(BlockPos origin, int range, Set<Item> items, Iterator<E> memories,
                                Function<E, Storage> readStorage, BooleanSupplier isCurrent) {
        this.origin = origin.immutable();
        this.rangeSquared = (long) range * range;
        this.items = Set.copyOf(items);
        this.memories = memories;
        this.readStorage = readStorage;
        this.isCurrent = isCurrent;
    }

    @Override
    public List<Stock> tick() {
        if (finished != null) return finished;
        try {
            if (!isCurrent.getAsBoolean()) return invalidate();
            long start = System.nanoTime();
            for (int tickSteps = 0; tickSteps < MAX_STEPS_PER_TICK; tickSteps++) {
                if (stacks.hasNext()) {
                    ItemStack stack = stacks.next();
                    if (items.contains(stack.getItem()) && ItemUtil.isPlainLoose(stack)) {
                        currentStock.merge(stack.getItem(), stack.getCount(), Math::addExact);
                    }
                } else {
                    appendStock();
                    if (!memories.hasNext()) return finish();
                    beginStorage(readStorage.apply(memories.next()));
                }
                if (System.nanoTime() - start >= TICK_BUDGET_NANOS) break;
            }
            return null;
        } catch (Throwable error) {
            Playercontrolpp.LOGGER.debug("Loose stock scan was invalidated", error);
            return invalidate();
        }
    }

    private void beginStorage(Storage storage) {
        if (!ContainerMenus.isPlainStorageBlock(storage.block())) return;

        BlockPos position = storage.position();
        if (position.distSqr(origin) > rangeSquared) return;
        List<BlockPos> connected = storage.connected();
        if (connected.size() > 1 || seenPositions.contains(position)) return;
        if (!connected.isEmpty()) {
            BlockPos other = connected.getFirst();
            if (seenPositions.contains(other)) return;
            seenPositions.add(other);
        }
        seenPositions.add(position);

        source = position.immutable();
        stacks = storage.items().iterator();
    }

    private void appendStock() {
        for (Map.Entry<Item, Integer> entry : currentStock.entrySet()) {
            stock.add(new Stock(entry.getKey(), entry.getValue(), source));
        }
        currentStock.clear();
    }

    private List<Stock> finish() {
        appendStock();
        stock.sort(Comparator.comparingDouble(entry -> entry.position().distSqr(origin)));
        finished = List.copyOf(stock);
        return finished;
    }

    private List<Stock> invalidate() {
        valid = false;
        finished = List.of();
        return finished;
    }

    @Override
    public boolean valid() { return valid; }
}
