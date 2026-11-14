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

import java.util.*;
import java.util.concurrent.*;

/** 主线程按预算复制区段，后台仅搜索快照；结果缓存随世界、位置、范围和来源集合失效。 */
public final class KnownMiningSurvey {
    public record Status(boolean ready, Map<String, List<BlockPos>> positions) {}
    private static final long CAPTURE_BUDGET_NANOS = 2_000_000;
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
                || job.origin.distSqr(mc.player.blockPosition()) > 64
                || (job.result != null && tick - job.finishedTick > 100)) {
            clear();
            job = new Job(mc.level, mc.player.blockPosition(), radius, requested);
        }
        return new Status(job.result != null, job.result == null ? Map.of() : job.result);
    }

    public static void tick() {
        tick++;
        if (job == null || job.result != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != job.world || mc.player == null) { clear(); return; }
        try {
            if (job.future != null) {
                if (job.future.isDone()) {
                    MiningSnapshotSearch.Result completed = job.future.get();
                    job.result = completed.positions();
                    job.finishedTick = tick;
                    Playercontrolpp.LOGGER.debug("[raw] mining survey: sections={} visited={} totalMs={}",
                            job.sectionCount, completed.visitedBlocks(), (System.nanoTime() - job.started) / 1_000_000);
                }
                return;
            }
            long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
            // Baritone 的缓存只在主线程访问；一次调用不可抢占，但每 tick 最多处理四种来源。
            int cachedRequests = 0;
            while (job.cacheIndex < job.orderedIds.size() && cachedRequests++ < 4 && System.nanoTime() < deadline) {
                String id = job.orderedIds.get(job.cacheIndex++);
                List<BlockPos> positions = BaritoneIntegration.getInstance().cachedMiningPositions(id, job.origin, job.radius);
                job.cached.put(id, positions.stream().filter(pos -> pos.distSqr(job.origin) <= (long) job.radius * job.radius)
                        .filter(pos -> !job.world.hasChunkAt(pos)
                                || id.equals(job.byBlock.get(job.world.getBlockState(pos).getBlock())))
                        .map(BlockPos::immutable).toList());
            }
            while (job.cacheIndex >= job.orderedIds.size() && job.chunkIndex < job.chunks.size()
                    && System.nanoTime() < deadline) {
                ChunkPos chunk = job.chunks.get(job.chunkIndex);
                int dx = Math.max(Math.max(chunk.getMinBlockX() - job.origin.getX(),
                        job.origin.getX() - chunk.getMinBlockX() - 15), 0);
                int dz = Math.max(Math.max(chunk.getMinBlockZ() - job.origin.getZ(),
                        job.origin.getZ() - chunk.getMinBlockZ() - 15), 0);
                if ((long) dx * dx + (long) dz * dz > (long) job.radius * job.radius) {
                    job.chunkIndex++; job.sectionIndex = 0; continue;
                }
                var loaded = job.world.getChunkSource().getChunk(chunk.getMinBlockX() >> 4, chunk.getMinBlockZ() >> 4, false);
                if (loaded == null) { job.chunkIndex++; job.sectionIndex = 0; continue; }
                var sections = loaded.getSections();
                if (job.sectionIndex >= sections.length) { job.chunkIndex++; job.sectionIndex = 0; continue; }
                int index = job.sectionIndex++;
                int sectionY = loaded.getSectionYFromSectionIndex(index) << 4;
                var section = sections[index];
                if (sectionY > job.origin.getY() + job.radius || sectionY + 15 < job.origin.getY() - job.radius
                        || section == null || section.hasOnlyAir()
                        || !section.maybeHas(state -> job.byBlock.containsKey(state.getBlock()))) continue;
                job.sections.add(new MiningSnapshotSearch.Section(new BlockPos(chunk.getMinBlockX(), sectionY,
                        chunk.getMinBlockZ()), section.getStates().copy()));
            }
            if (job.cacheIndex >= job.orderedIds.size() && job.chunkIndex >= job.chunks.size()) {
                List<MiningSnapshotSearch.Section> sections = List.copyOf(job.sections);
                Map<Block, String> sources = Map.copyOf(job.byBlock);
                Map<String, List<BlockPos>> cached = Map.copyOf(job.cached);
                BlockPos origin = job.origin;
                int radius = job.radius;
                job.sectionCount = sections.size();
                job.future = WORKER.submit(() -> MiningSnapshotSearch.search(sections, sources, cached, origin, radius));
                job.sections.clear();
            }
        } catch (Throwable error) {
            Playercontrolpp.LOGGER.warn("Unable to inspect mining snapshots", error);
            job.result = Map.of();
            job.finishedTick = tick;
        }
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
        final long started = System.nanoTime();
        int cacheIndex, chunkIndex, sectionIndex, sectionCount;
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
            int chunkRadius = (radius + 15) / 16;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                chunks.add(new ChunkPos((origin.getX() >> 4) + dx, (origin.getZ() >> 4) + dz));
            }
            chunks.sort(Comparator.comparingDouble(chunk -> {
                double dx = chunk.getMiddleBlockX() - origin.getX(), dz = chunk.getMiddleBlockZ() - origin.getZ();
                return dx * dx + dz * dz;
            }));
        }
    }
}
