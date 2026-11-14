package com.alonediamond.playercontrolpp.mixin.compat.baritone;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * {@link BaritoneIntegration} 的直连实现，由 MixinPlugin 在 Baritone 在场时注入。
 *
 * <p>Baritone 的 API 从 1.11.2（1.21.1）到 1.18.0（26.2）逐版本核对过，签名完全一致，
 * zbaritone / baritone-meteor 等 fork 也只改 ModID 不改 API，无需任何版本分支。
 *
 * <p>方法体捕获 {@link Throwable} 的理由见 {@code LitematicaIntegrationImpl}：fork 的
 * API 漂移会以 {@code Error} 形式出现，要与旧反射实现一样静默降级。
 */
@Mixin(BaritoneIntegration.class)
public abstract class BaritoneIntegrationImpl {

    @Unique
    private static IBaritone primaryBaritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    @Overwrite(remap = false)
    public void pathTo(BlockPos target) {
        try {
            cancelPathing();

            primaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
        } catch (Throwable e) {
            // 兜底：改用执行命令的方式
            try {
                primaryBaritone().getCommandManager().execute(
                        String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ()));
            } catch (Throwable ignored) {
                // API 和命令兜底都失败了，说明这个 Baritone 分支两者都不暴露。刻意保持沉默：
                // 调用方（BaritonePathingController）会发现寻路始终没开始并告知玩家，
                // 那条消息比一段异常栈有用得多。
            }
        }
    }

    /** 取消所有正在进行的 Baritone 寻路与自定义目标。 */
    @Overwrite(remap = false)
    public boolean cancelPathing() {
        try {
            IBaritone baritone = primaryBaritone();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCustomGoalProcess().onLostControl();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 查询 Baritone 当前是否在寻路。 */
    @Overwrite(remap = false)
    public boolean isPathing() {
        try {
            return primaryBaritone().getPathingBehavior().isPathing();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean isBuilderActive() {
        try {
            return primaryBaritone().getBuilderProcess().isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean isBuilderPaused() {
        try {
            return primaryBaritone().getBuilderProcess().isPaused();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean startLitematicaBuild(int schematicIndex) {
        try {
            IBuilderProcess builderProcess = primaryBaritone().getBuilderProcess();
            builderProcess.buildOpenLitematic(schematicIndex);
            // buildOpenLitematic 返回 void 且自己吞掉失败——索引上没有 placement 时它只打一行
            // 日志，进程照旧空闲。判据是调用之后进程是否活动，而它是在客户端线程上同步设置的。
            return builderProcess.isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean startLitematicaLayerBuild(int schematicIndex, int worldY) {
        try {
            LitematicaIntegration litematica = LitematicaIntegration.getInstance();
            var world = litematica.getSchematicWorld();
            List<LitematicaIntegration.SelectionBounds> regions = litematica.getPlacementRegionBounds(schematicIndex)
                    .stream().filter(box -> worldY >= box.min().getY() && worldY <= box.max().getY()).toList();
            if (world == null || regions.isEmpty()) return false;
            int minX = regions.stream().mapToInt(box -> box.min().getX()).min().orElseThrow();
            int minZ = regions.stream().mapToInt(box -> box.min().getZ()).min().orElseThrow();
            int maxX = regions.stream().mapToInt(box -> box.max().getX()).max().orElseThrow();
            int maxZ = regions.stream().mapToInt(box -> box.max().getZ()).max().orElseThrow();
            // 稀疏区域之间也会被 Builder 扫描，限制外框面积以免一次 tick 分配或遍历无界。
            if (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > 1_000_000L) return false;
            int width = maxX - minX + 1;
            int length = maxZ - minZ + 1;
            BlockState[][] states = new BlockState[width][length];
            for (LitematicaIntegration.SelectionBounds box : regions) {
                for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                    for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                        BlockPos pos = new BlockPos(x, worldY, z);
                        if ((x == box.min().getX() || (x & 15) == 0)
                                && (z == box.min().getZ() || (z & 15) == 0)
                                && !litematica.isSchematicChunkLoaded(pos)) return false;
                        BlockState state = world.getBlockState(pos);
                        if (!state.is(Blocks.STRUCTURE_VOID)) states[x - minX][z - minZ] = state;
                    }
                }
            }
            ISchematic layer = layerSchematic(states, width, length);
            return buildExactLayer(layer, new BlockPos(minX, worldY, minZ));
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to start a bounded Litematica layer build", e);
            return false;
        }
    }

    @Unique
    private static ISchematic layerSchematic(BlockState[][] states, int width, int length) {
        return new ISchematic() {
            @Override
            public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                return y == 0 && x >= 0 && x < width && z >= 0 && z < length
                        && states[x][z] != null;
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                return states[x][z];
            }

            @Override
            public int widthX() { return width; }

            @Override
            public int heightY() { return 1; }

            @Override
            public int lengthZ() { return length; }
        };
    }

    @Unique
    private static boolean buildExactLayer(ISchematic layer, BlockPos origin) {
        var settings = BaritoneAPI.getSettings();
        // 重复建造在后续 tick 读取全局设置，无法靠同步调用期间覆盖；拒绝避免越界复制。
        if (!Vec3i.ZERO.equals(settings.buildRepeat.value)) return false;
        boolean orientationX = settings.schematicOrientationX.value;
        boolean orientationY = settings.schematicOrientationY.value;
        boolean orientationZ = settings.schematicOrientationZ.value;
        Rotation rotation = settings.buildSchematicRotation.value;
        Mirror mirror = settings.buildSchematicMirror.value;
        var substitutes = settings.buildSubstitutes.value;
        int startAtLayer = settings.startAtLayer.value;
        try {
            // 投影世界已经应用 placement 的变换，Builder 的二次变换会破坏世界坐标裁剪。
            settings.schematicOrientationX.value = false;
            settings.schematicOrientationY.value = false;
            settings.schematicOrientationZ.value = false;
            settings.buildSchematicRotation.value = Rotation.NONE;
            settings.buildSchematicMirror.value = Mirror.NONE;
            settings.buildSubstitutes.value = Map.of();
            settings.startAtLayer.value = 0;
            IBuilderProcess builder = primaryBaritone().getBuilderProcess();
            builder.build("PlayerControl++ layer " + origin.getY(), layer, origin);
            return builder.isActive();
        } finally {
            settings.schematicOrientationX.value = orientationX;
            settings.schematicOrientationY.value = orientationY;
            settings.schematicOrientationZ.value = orientationZ;
            settings.buildSchematicRotation.value = rotation;
            settings.buildSchematicMirror.value = mirror;
            settings.buildSubstitutes.value = substitutes;
            settings.startAtLayer.value = startAtLayer;
        }
    }

    @Overwrite(remap = false)
    public List<BlockPos> findKnownMiningPositions(String blockId, int radius) {
        return findKnownMiningSources(List.of(blockId), radius).getOrDefault(blockId, List.of());
    }

    @Overwrite(remap = false)
    public Map<String, List<BlockPos>> findKnownMiningSources(List<String> blockIds, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || radius <= 0 || blockIds.isEmpty()) return Map.of();
        try {
            BlockPos origin = mc.player.blockPosition();
            double radiusSquared = (double) radius * radius;
            Map<Block, String> byBlock = new HashMap<>();
            Map<String, PriorityQueue<BlockPos>> positions = new LinkedHashMap<>();
            for (String id : blockIds) {
                Identifier key = Identifier.tryParse(id);
                if (key == null) continue;
                Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
                if (block == null) continue;
                byBlock.put(block, id);
                positions.putIfAbsent(id, new PriorityQueue<>(Comparator.comparingDouble(origin::distSqr).reversed()));
            }
            if (positions.isEmpty()) return Map.of();
            var worldData = primaryBaritone().getPlayerContext().worldData();
            int regionRadius = (radius + 511) / 512 + 1;
            if (worldData != null) {
                for (String id : positions.keySet()) {
                    for (BlockPos pos : worldData.getCachedWorld().getLocationsOf(id, 64,
                            origin.getX(), origin.getZ(), regionRadius * regionRadius * 2)) {
                        if (pos.distSqr(origin) > radiusSquared) continue;
                        if (mc.level.hasChunkAt(pos) && !id.equals(byBlock.get(mc.level.getBlockState(pos).getBlock()))) continue;
                        rememberSource(positions.get(id), origin, pos);
                    }
                }
            }
            scanLoadedSources(mc, origin, radius, byBlock, positions);
            Map<String, List<BlockPos>> result = new LinkedHashMap<>();
            positions.forEach((id, found) -> result.put(id, found.stream()
                    .sorted(Comparator.comparingDouble(origin::distSqr)).toList()));
            return Map.copyOf(result);
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to inspect known Baritone mining sources", e);
            return Map.of();
        }
    }

    @Unique
    private static void scanLoadedSources(Minecraft mc, BlockPos origin, int radius, Map<Block, String> byBlock,
                                          Map<String, PriorityQueue<BlockPos>> positions) {
        // 限定已加载区块、球形距离和最多八区块观察半径，不扫描所有高度或加载新区块。
        int chunkRadius = Math.min(8, (radius + 15) / 16);
        int centerX = origin.getX() >> 4;
        int centerZ = origin.getZ() >> 4;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                chunks.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }
        chunks.sort(Comparator.comparingDouble(chunk -> {
            double dx = chunk.getMiddleBlockX() - origin.getX();
            double dz = chunk.getMiddleBlockZ() - origin.getZ();
            return dx * dx + dz * dz;
        }));
        double radiusSquared = (double) radius * radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ChunkPos chunk : chunks) {
            var loaded = mc.level.getChunkSource().getChunk(chunk.getMinBlockX() >> 4, chunk.getMinBlockZ() >> 4, false);
            if (loaded == null) continue;
            var sections = loaded.getSections();
            for (int index = 0; index < sections.length; index++) {
                int sectionY = loaded.getSectionYFromSectionIndex(index) << 4;
                int minY = Math.max(0, origin.getY() - radius - sectionY);
                int maxY = Math.min(15, origin.getY() + radius - sectionY);
                var section = sections[index];
                if (minY > maxY || section == null || section.hasOnlyAir()
                        || !section.maybeHas(state -> byBlock.containsKey(state.getBlock()))) continue;
                for (int x = 0; x < 16; x++) {
                    int worldX = chunk.getMinBlockX() + x;
                    double dx = worldX - origin.getX();
                    for (int z = 0; z < 16; z++) {
                        int worldZ = chunk.getMinBlockZ() + z;
                        double dz = worldZ - origin.getZ();
                        double horizontal = dx * dx + dz * dz;
                        if (horizontal > radiusSquared) continue;
                        for (int y = minY; y <= maxY; y++) {
                            double dy = sectionY + y - origin.getY();
                            if (horizontal + dy * dy > radiusSquared) continue;
                            String id = byBlock.get(section.getBlockState(x, y, z).getBlock());
                            if (id != null) rememberSource(positions.get(id), origin, pos.set(worldX, sectionY + y, worldZ));
                        }
                    }
                }
            }
        }
    }

    @Unique
    private static void rememberSource(PriorityQueue<BlockPos> found, BlockPos origin, BlockPos pos) {
        if (found.size() >= 64 && pos.distSqr(origin) >= found.peek().distSqr(origin)) return;
        if (found.contains(pos)) return;
        found.add(pos.immutable());
        if (found.size() > 64) found.poll();
    }

    @Overwrite(remap = false)
    public boolean resumeBuilder() {
        try {
            primaryBaritone().getBuilderProcess().resume();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public boolean allowsInventory() {
        try {
            return Boolean.TRUE.equals(BaritoneAPI.getSettings().allowInventory.value);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 与 {@code #mine <quantity> <block>} 同一条路径：Baritone 自己搜索、走过去、换工具。 */
    @Overwrite(remap = false)
    public void mine(String blockId, int quantity) {
        try {
            primaryBaritone().getMineProcess().mineByName(quantity, blockId);
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to start Baritone mining for {}", blockId, e);
        }
    }

    @Overwrite(remap = false)
    public boolean isMining() {
        try {
            return primaryBaritone().getMineProcess().isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 只取消挖掘进程。
     *
     * <p>不用 {@code cancelEverything()}：那条路会给所有进程发 {@code onLostControl()}，
     * 万一玩家同时在用别的东西（例如建造）会被一起打断。停止备货时的整体取消另走
     * {@link #cancelPathing()}。
     */
    @Overwrite(remap = false)
    public void cancelMining() {
        try {
            primaryBaritone().getMineProcess().cancel();
        } catch (Throwable e) {
            // 进程本来就不在，或 fork 漂移；两种都不需要处理。
        }
    }
}
