package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 主线程按预算复制区段，后台仅搜索快照；结果缓存随世界、位置、范围和来源集合失效。 */
public final class KnownMiningSurvey {
    public record Status(boolean ready, Map<String, List<BlockPos>> positions) {}

    private static final long CAPTURE_BUDGET_NANOS = 2_000_000;
    private static final int MAX_CACHE_REQUESTS_PER_TICK = 4;
    private static final int RESULT_LIFETIME_TICKS = 100;
    private static final int REUSE_DISTANCE_SQUARED = 8 * 8;
    private static final int CHUNK_SIZE = 16;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "PlayerControl++ mining snapshots");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static Job job;
    private static long tick;
    private KnownMiningSurvey() {}

    public static Status poll(List<String> ids, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || radius <= 0 || ids.isEmpty()) {
            clear();
            return new Status(true, Map.of());
        }
        Set<String> requested = Set.copyOf(ids);
        if (job == null || job.world != mc.level || job.radius != radius || !job.ids.equals(requested)
                || job.origin.distSqr(mc.player.blockPosition()) > REUSE_DISTANCE_SQUARED
                || (job.result != null && tick - job.finishedTick > RESULT_LIFETIME_TICKS)) {
            clear();
            job = new Job(mc.level, mc.player.blockPosition(), radius, requested);
        }
        return new Status(job.result != null, job.result == null ? Map.of() : job.result);
    }

    public static void tick() {
        tick++;
        if (job == null || job.result != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != job.world || mc.player == null) {
            clear();
            return;
        }
        try {
            if (job.future != null) {
                if (job.future.isDone()) {
                    job.result = job.future.get().positions();
                    job.finishedTick = tick;
                }
                return;
            }
            long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
            captureCachedSources(job, deadline);
            while (job.cacheIndex >= job.orderedIds.size() && job.chunkIndex < job.chunks.size()
                    && System.nanoTime() < deadline) {
                captureNextSection(job);
            }
            if (job.cacheIndex >= job.orderedIds.size() && job.chunkIndex >= job.chunks.size()) {
                submitSearch(job);
            }
        } catch (Throwable error) {
            Playercontrolpp.LOGGER.warn("Unable to inspect mining snapshots", error);
            job.result = Map.of();
            job.finishedTick = tick;
        }
    }

    private static void captureCachedSources(Job current, long deadline) {
        // Baritone 的缓存只在主线程访问；单次调用不可抢占，预算只限制下一次调用。
        int requests = 0;
        while (current.cacheIndex < current.orderedIds.size()
                && requests++ < MAX_CACHE_REQUESTS_PER_TICK && System.nanoTime() < deadline) {
            String id = current.orderedIds.get(current.cacheIndex++);
            List<BlockPos> positions = BaritoneIntegration.getInstance()
                    .cachedMiningPositions(id, current.origin, current.radius);
            current.cached.put(id, positions.stream()
                    .filter(pos -> pos.distSqr(current.origin) <= (long) current.radius * current.radius)
                    .filter(pos -> !current.world.hasChunkAt(pos)
                            || id.equals(current.byBlock.get(current.world.getBlockState(pos).getBlock())))
                    .map(BlockPos::immutable)
                    .toList());
        }
    }

    private static void captureNextSection(Job current) {
        ChunkPos chunk = current.chunks.get(current.chunkIndex);
        int dx = distanceToChunk(current.origin.getX(), chunk.getMinBlockX());
        int dz = distanceToChunk(current.origin.getZ(), chunk.getMinBlockZ());
        if ((long) dx * dx + (long) dz * dz > (long) current.radius * current.radius) {
            current.advanceChunk();
            return;
        }
        LevelChunk loaded = current.world.getChunkSource().getChunk(
                chunk.getMinBlockX() >> 4, chunk.getMinBlockZ() >> 4, false);
        if (loaded == null || current.sectionIndex >= loaded.getSections().length) {
            current.advanceChunk();
            return;
        }
        int index = current.sectionIndex++;
        int sectionY = loaded.getSectionYFromSectionIndex(index) * CHUNK_SIZE;
        LevelChunkSection section = loaded.getSections()[index];
        if (sectionY > current.origin.getY() + current.radius
                || sectionY + CHUNK_SIZE - 1 < current.origin.getY() - current.radius
                || section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> current.byBlock.containsKey(state.getBlock()))) {
            return;
        }
        BlockPos origin = new BlockPos(chunk.getMinBlockX(), sectionY, chunk.getMinBlockZ());
        current.sections.add(new MiningSnapshotSearch.Section(origin, section.getStates().copy()));
    }

    private static int distanceToChunk(int coordinate, int minimum) {
        return Math.max(Math.max(minimum - coordinate, coordinate - minimum - (CHUNK_SIZE - 1)), 0);
    }

    private static void submitSearch(Job current) {
        // lambda 只捕获副本，不能把持有实时世界的 Job 传给工作线程。
        List<MiningSnapshotSearch.Section> sections = List.copyOf(current.sections);
        Map<Block, String> sources = Map.copyOf(current.byBlock);
        Map<String, List<BlockPos>> cached = Map.copyOf(current.cached);
        BlockPos origin = current.origin;
        int radius = current.radius;
        current.future = WORKER.submit(() -> MiningSnapshotSearch.search(sections, sources, cached, origin, radius));
        current.sections.clear();
    }

    public static void clear() {
        if (job != null && job.future != null) job.future.cancel(true);
        WORKER.getQueue().clear();
        job = null;
    }

    private static final class Job {
        final ClientLevel world;
        final BlockPos origin;
        final int radius;
        final Set<String> ids;
        final List<String> orderedIds;
        final Map<Block, String> byBlock = new HashMap<>();
        final Map<String, List<BlockPos>> cached = new HashMap<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final List<MiningSnapshotSearch.Section> sections = new ArrayList<>();
        int cacheIndex;
        int chunkIndex;
        int sectionIndex;
        long finishedTick;
        Future<MiningSnapshotSearch.Result> future;
        Map<String, List<BlockPos>> result;

        Job(ClientLevel world, BlockPos origin, int radius, Set<String> ids) {
            this.world = world;
            this.origin = origin.immutable();
            this.radius = radius;
            this.ids = ids;
            orderedIds = ids.stream().sorted().toList();
            for (String id : orderedIds) {
                Identifier key = Identifier.tryParse(id);
                if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(block -> byBlock.put(block, id));
            }
            int chunkRadius = (radius + CHUNK_SIZE - 1) / CHUNK_SIZE;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    chunks.add(new ChunkPos((origin.getX() >> 4) + dx, (origin.getZ() >> 4) + dz));
                }
            }
            chunks.sort(Comparator.comparingDouble(chunk -> {
                double dx = chunk.getMiddleBlockX() - origin.getX();
                double dz = chunk.getMiddleBlockZ() - origin.getZ();
                return dx * dx + dz * dz;
            }));
        }

        void advanceChunk() {
            chunkIndex++;
            sectionIndex = 0;
        }
    }
}
