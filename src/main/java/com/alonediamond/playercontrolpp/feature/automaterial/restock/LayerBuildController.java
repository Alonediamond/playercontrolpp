package com.alonediamond.playercontrolpp.feature.automaterial.restock;

import com.alonediamond.playercontrolpp.config.BuildLayerMode;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.SelectionBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;

import java.util.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** 逐层建造与世界确认；每 tick 有界扫描，未加载区块始终不能作为建完的证据。 */
final class LayerBuildController {
    enum Result { ACTIVE, ADVANCED, COMPLETE, CHANGED, STOPPED, UNLOADED, START_FAILED }
    private static final int SCAN_BUDGET = 4096;
    private final LitematicaIntegration litematica = LitematicaIntegration.getInstance();
    private final BaritoneIntegration baritone = BaritoneIntegration.getInstance();
    private List<SelectionBounds> bounds = List.of();
    private BlockGetter schematic;
    private BuildLayerMode mode;
    private int y, minY, maxY, region, x, z, ticks;
    private final LayerBuildRules.StableCompletion completion = new LayerBuildRules.StableCompletion();
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private long scanHash;
    private Long blueprintHash;
    private boolean canAnalyze;
    private boolean unknown, missing;
    private final Map<Item, Integer> scanningNeeds = new LinkedHashMap<>();
    private List<LitematicaIntegration.MaterialEntry> lastNeeds = List.of();
    private boolean active;

    boolean isActive() { return active; }
    int layerY() { return y; }
    List<LitematicaIntegration.MaterialEntry> materialNeeds() { return lastNeeds; }
    boolean canAnalyze() { return canAnalyze; }

    boolean start() {
        reset();
        mode = (BuildLayerMode) Configs.Restocks.BUILD_LAYER_MODE.getOptionListValue();
        var layer = litematica.getSingleLayerY();
        bounds = List.copyOf(litematica.getPlacementRegionBounds(0));
        schematic = litematica.getSchematicWorld();
        if (mode == BuildLayerMode.OFF || layer.isEmpty() || bounds.isEmpty() || schematic == null) return false;
        y = layer.getAsInt();
        minY = bounds.stream().mapToInt(box -> box.min().getY()).min().orElse(y);
        maxY = bounds.stream().mapToInt(box -> box.max().getY()).max().orElse(y);
        if (y < minY || y > maxY) return false;
        final int initialY = y;
        if (bounds.stream().noneMatch(box -> initialY >= box.min().getY() && initialY <= box.max().getY())) {
            var next = LayerBuildRules.nextLayer(bounds, y, mode == BuildLayerMode.UP ? 1 : -1);
            if (next.isEmpty() || !litematica.setSingleLayerY(next.getAsInt())) return false;
            y = next.getAsInt();
        }
        active = true;
        beginScan();
        // 已有完整蓝图建造也必须替换，否则它仍会越层。
        baritone.cancelPathing();
        return relaunch();
    }

    boolean relaunch() { return active && baritone.startLitematicaLayerBuild(0, y); }

    Result tick(Minecraft mc) {
        ticks++;
        if (!active || litematica.getSingleLayerY().orElse(Integer.MIN_VALUE) != y
                || litematica.getSchematicWorld() != schematic
                || !bounds.equals(litematica.getPlacementRegionBounds(0))) return Result.CHANGED;
        int budget = SCAN_BUDGET;
        while (region < bounds.size() && budget-- > 0) {
            SelectionBounds box = bounds.get(region);
            if (y >= box.min().getY() && y <= box.max().getY()) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!visited.add(pos.asLong())) { advancePosition(box); continue; }
                if (!mc.level.hasChunkAt(pos) || !litematica.isSchematicChunkLoaded(pos)) unknown = true;
                else {
                    var expected = schematic.getBlockState(pos);
                    scanHash += pos.asLong() ^ ((long) expected.hashCode() * 0x9E3779B9L);
                    if (!expected.is(Blocks.STRUCTURE_VOID) && !expected.equals(mc.level.getBlockState(pos))) {
                        missing = true;
                        Item item = expected.getBlock().asItem();
                        if (item != Items.AIR) scanningNeeds.merge(item, 1, Integer::sum);
                    }
                }
            }
            advancePosition(box);
        }
        if (region < bounds.size()) return Result.ACTIVE;
        boolean wasUnknown = unknown;
        boolean wasMissing = missing;
        if (!wasUnknown) {
            if (blueprintHash != null && blueprintHash != scanHash) return Result.CHANGED;
            blueprintHash = scanHash;
        }
        lastNeeds = scanningNeeds.entrySet().stream().map(entry -> new LitematicaIntegration.MaterialEntry(
                new ItemStack(entry.getKey()), entry.getValue(), 0, false)).toList();
        canAnalyze = !wasUnknown && wasMissing;
        beginScan();
        boolean complete = completion.confirm(ticks, wasUnknown, wasMissing,
                Configs.Restocks.BUILD_LAYER_STABLE_TICKS.getIntegerValue());
        if (wasUnknown) return Result.UNLOADED;
        if (wasMissing) return baritone.isBuilderActive() ? Result.ACTIVE : Result.STOPPED;
        if (!complete) return Result.ACTIVE;
        var nextLayer = LayerBuildRules.nextLayer(bounds, y, mode == BuildLayerMode.UP ? 1 : -1);
        if (nextLayer.isEmpty()) { active = false; return Result.COMPLETE; }
        int next = nextLayer.getAsInt();
        baritone.cancelPathing();
        if (!litematica.setSingleLayerY(next)) return Result.CHANGED;
        y = next;
        completion.reset();
        blueprintHash = null;
        canAnalyze = false;
        lastNeeds = List.of();
        beginScan();
        return relaunch() ? Result.ADVANCED : Result.START_FAILED;
    }

    private void advancePosition(SelectionBounds box) {
        if (++x > box.max().getX()) {
            x = box.min().getX();
            if (++z > box.max().getZ()) { region++; prepareRegion(); }
        }
    }

    private void beginScan() {
        region = 0;
        unknown = false;
        missing = false;
        scanningNeeds.clear();
        visited.clear();
        scanHash = 0;
        prepareRegion();
    }

    private void prepareRegion() {
        // 不在当前Y的子区无需逐格扫描。
        while (region < bounds.size() && (y < bounds.get(region).min().getY() || y > bounds.get(region).max().getY())) region++;
        if (region < bounds.size()) { x = bounds.get(region).min().getX(); z = bounds.get(region).min().getZ(); }
    }

    void reset() {
        active = false;
        bounds = List.of();
        schematic = null;
        completion.reset();
        blueprintHash = null;
        canAnalyze = false;
        ticks = 0;
        lastNeeds = List.of();
    }
}
