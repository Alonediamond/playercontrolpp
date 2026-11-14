package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** 不启动世界，验证快照搜索的复制隔离、裁剪和取消。 */
public final class MiningSnapshotSearchRegression {
    private static final String STONE = "minecraft:stone";
    private MiningSnapshotSearchRegression() {}

    public static int run() {
        int checks = 0;
        BlockPos origin = BlockPos.ZERO;
        PalettedContainer<BlockState> live = emptyStates();
        live.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        PalettedContainer<BlockState> snapshot = live.copy();
        live.set(0, 0, 0, Blocks.AIR.defaultBlockState());
        var copied = search(List.of(section(origin, snapshot)), Map.of(), origin, 32);
        check(copied.positions().get(STONE).equals(List.of(origin)), "复制后原容器变化不影响快照结果");
        checks++;
        check(search(List.of(section(origin, live)), Map.of(), origin, 32)
                .positions().get(STONE).isEmpty(), "原容器变化只影响后续新快照");
        checks++;

        PalettedContainer<BlockState> dense = emptyStates();
        List<BlockPos> expected = new ArrayList<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            dense.set(x, 0, z, Blocks.STONE.defaultBlockState());
            expected.add(new BlockPos(x, 0, z));
        }
        var nearest = search(List.of(section(origin, dense)), Map.of(), origin, 32);
        List<BlockPos> positions = nearest.positions().get(STONE);
        check(positions.size() == 64 && positions.contains(origin), "仅保留最近 64 个位置");
        checks++;
        expected.sort(Comparator.comparingDouble(origin::distSqr));
        double cutoff = expected.get(63).distSqr(origin);
        check(positions.stream().allMatch(pos -> pos.distSqr(origin) <= cutoff)
                        && expected.stream().filter(pos -> pos.distSqr(origin) < cutoff).allMatch(positions::contains),
                "裁剪结果不遗漏严格更近的位置");
        checks++;
        boolean ordered = true;
        for (int i = 1; i < positions.size(); i++) {
            if (positions.get(i - 1).distSqr(origin) > positions.get(i).distSqr(origin)) ordered = false;
        }
        check(ordered, "输出按距离从近到远排序");
        checks++;

        BlockPos duplicate = new BlockPos(1, 0, 0);
        PalettedContainer<BlockState> one = emptyStates();
        one.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        var deduplicated = search(List.of(section(origin, one)),
                Map.of(STONE, List.of(duplicate, duplicate, new BlockPos(100, 0, 0))), origin, 32);
        check(deduplicated.positions().get(STONE).equals(List.of(duplicate)),
                "缓存重复位置与快照命中只保留一次，范围外缓存不进入结果");
        checks++;

        BlockPos far = new BlockPos(256, 0, 0);
        PalettedContainer<BlockState> distant = emptyStates();
        distant.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        var pruned = search(List.of(section(far, distant)), Map.of(), origin, 32);
        check(pruned.visitedBlocks() == 0 && pruned.positions().get(STONE).isEmpty(),
                "远区段在逐方块扫描前剪枝");
        checks++;

        var capped = search(List.of(section(new BlockPos(32, 0, 0), distant), section(origin, dense)),
                Map.of(), origin, 64);
        check(capped.visitedBlocks() == 4096
                        && capped.positions().get(STONE).stream().noneMatch(pos -> pos.getX() >= 32),
                "先扫描近区段，已有 64 个更近位置时跳过远区段");
        checks++;

        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try {
            search(List.of(section(origin, dense)), Map.of(), origin, 32);
        } catch (CancellationException expectedCancellation) {
            cancelled = true;
        } finally {
            Thread.interrupted();
        }
        check(cancelled, "线程取消时中断搜索");
        checks++;
        return checks;
    }

    private static MiningSnapshotSearch.Section section(BlockPos origin, PalettedContainer<BlockState> states) {
        return new MiningSnapshotSearch.Section(origin, states);
    }

    private static MiningSnapshotSearch.Result search(List<MiningSnapshotSearch.Section> sections,
                                                       Map<String, List<BlockPos>> cached, BlockPos origin, int radius) {
        Map<Block, String> sources = Map.of(Blocks.STONE, STONE);
        return MiningSnapshotSearch.search(sections, sources, cached, origin, radius);
    }

    private static PalettedContainer<BlockState> emptyStates() {
        //#if MC >= 12110
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        //#else
        //$$ return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
        //$$         PalettedContainer.Strategy.SECTION_STATES);
        //#endif
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
    }
}
