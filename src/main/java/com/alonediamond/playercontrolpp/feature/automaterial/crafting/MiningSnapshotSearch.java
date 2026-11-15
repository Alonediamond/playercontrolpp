package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;

/** 只处理独立复制的区段调色板；工作线程不读取世界、玩家或 Baritone 缓存。 */
public final class MiningSnapshotSearch {
    private static final int SECTION_SIZE = 16;
    private static final int MAX_POSITIONS_PER_SOURCE = 64;

    public record Section(BlockPos origin, PalettedContainer<BlockState> states) {}
    public record Result(Map<String, List<BlockPos>> positions, long visitedBlocks) {}
    private MiningSnapshotSearch() {}

    public static Result search(List<Section> sections, Map<Block, String> sources,
                                Map<String, List<BlockPos>> cached, BlockPos origin, int radius) {
        Map<String, PriorityQueue<BlockPos>> found = new LinkedHashMap<>();
        for (String id : sources.values()) {
            found.computeIfAbsent(id,
                    ignored -> new PriorityQueue<>(Comparator.comparingDouble(origin::distSqr).reversed()));
        }
        double radiusSq = (double) radius * radius;
        cached.forEach((id, positions) -> {
            if (!found.containsKey(id)) return;
            for (BlockPos pos : positions) {
                if (pos.distSqr(origin) <= radiusSq) remember(found.get(id), origin, pos);
            }
        });
        List<Section> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingDouble(section -> distanceSq(origin, section.origin)));
        long visited = 0;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (Section section : ordered) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            double nearest = distanceSq(origin, section.origin);
            if (nearest > radiusSq || !section.states.maybeHas(state -> {
                String id = sources.get(state.getBlock());
                if (id == null) return false;
                PriorityQueue<BlockPos> queue = found.get(id);
                return queue.size() < MAX_POSITIONS_PER_SOURCE || nearest <= queue.peek().distSqr(origin);
            })) continue;
            for (int x = 0; x < SECTION_SIZE; x++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int y = 0; y < SECTION_SIZE; y++) {
                        position.set(section.origin.getX() + x, section.origin.getY() + y, section.origin.getZ() + z);
                        if (position.distSqr(origin) > radiusSq) continue;
                        visited++;
                        String id = sources.get(section.states.get(x, y, z).getBlock());
                        if (id != null) remember(found.get(id), origin, position);
                    }
                }
            }
        }
        Map<String, List<BlockPos>> result = new LinkedHashMap<>();
        found.forEach((id, queue) -> result.put(id, queue.stream()
                .sorted(Comparator.comparingDouble(origin::distSqr))
                .toList()));
        return new Result(Map.copyOf(result), visited);
    }

    private static void remember(PriorityQueue<BlockPos> queue, BlockPos origin, BlockPos position) {
        if (queue.size() >= MAX_POSITIONS_PER_SOURCE && position.distSqr(origin) >= queue.peek().distSqr(origin)) return;
        if (!queue.contains(position)) queue.add(position.immutable());
        if (queue.size() > MAX_POSITIONS_PER_SOURCE) queue.poll();
    }

    private static double distanceSq(BlockPos point, BlockPos section) {
        double x = distanceToSection(point.getX(), section.getX());
        double y = distanceToSection(point.getY(), section.getY());
        double z = distanceToSection(point.getZ(), section.getZ());
        return x * x + y * y + z * z;
    }

    private static int distanceToSection(int coordinate, int minimum) {
        return Math.max(Math.max(minimum - coordinate, coordinate - minimum - (SECTION_SIZE - 1)), 0);
    }
}
