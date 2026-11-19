package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.integration.CachedLooseStockScan;
import com.alonediamond.playercontrolpp.integration.CachedLooseStockScan.Storage;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration.Stock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

final class CachedLooseStockRegression {
    private CachedLooseStockRegression() {}

    static int run() {
        BlockPos primary = new BlockPos(1, 64, 0);
        BlockPos connected = new BlockPos(2, 64, 0);
        ItemStack ordinary = new ItemStack(Items.OAK_PLANKS, 5);
        ItemStack renamed = new ItemStack(Items.OAK_PLANKS, 20);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("reserve"));
        Storage first = new Storage(primary, Blocks.CHEST, List.of(connected), List.of(ordinary, renamed));
        List<Storage> memories = List.of(first,
                new Storage(connected, Blocks.CHEST, List.of(primary), List.of(ordinary)),
                new Storage(new BlockPos(12, 64, 0), Blocks.BARREL, List.of(), List.of(ordinary)),
                new Storage(new BlockPos(3, 64, 0), Blocks.HOPPER, List.of(), List.of(ordinary)));
        CachedLooseStockScan<Storage> scan = new CachedLooseStockScan<>(
                new BlockPos(0, 64, 0), 10, Set.of(Items.OAK_PLANKS), memories.iterator(),
                Function.identity(), () -> true);
        List<Stock> stock;
        do {
            stock = scan.tick();
        } while (stock == null);
        check(stock.equals(List.of(new Stock(Items.OAK_PLANKS, 5, primary))),
                "只计有限范围内的普通木板，双箱不重复计入");

        AtomicBoolean current = new AtomicBoolean(true);
        CachedLooseStockScan<Storage> stale = new CachedLooseStockScan<>(
                new BlockPos(0, 64, 0), 10, Set.of(Items.OAK_PLANKS),
                Collections.nCopies(200, first).iterator(), Function.identity(), current::get);
        check(stale.tick() == null, "大缓存的扫描跨 tick 推进");
        current.set(false);
        check(stale.tick().isEmpty() && !stale.valid(), "缓存世代变化丢弃已采集的库存");
        return 3;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
