package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import java.util.*;

/** 备货和建造续料共享的缓存查询边界：当前维度、有限范围、去重。 */
public final class CachedContainerSource {
    private final ChestTrackerIntegration tracker = ChestTrackerIntegration.getInstance();
    public record Scope(int range, String error) {}

    public Scope scope(int limit) {
        if (!tracker.isLoaded() || !tracker.hasLoadedMemoryBank() || tracker.getCurrentDimensionKey() == null) {
            return new Scope(0, "playercontrolpp.message.baritone.no_cache");
        }
        int range = tracker.getSearchRange();
        if (range < 0) range = tracker.getListRange();
        if (range < 0) return new Scope(0, "playercontrolpp.message.baritone.no_cache");
        if (limit > 0) range = Math.min(range, limit);
        if (range == Integer.MAX_VALUE) return new Scope(0, "playercontrolpp.message.baritone.range_infinite");
        return new Scope(range, null);
    }

    public List<BlockPos> find(Item item, BlockPos origin, int range, boolean boxesFirst, boolean includeBoxes) {
        Set<BlockPos> found = new LinkedHashSet<>();
        if (boxesFirst && includeBoxes) found.addAll(tracker.searchShulkerBoxWithItem(item, origin, range));
        found.addAll(tracker.searchItem(item, origin, range));
        if (!boxesFirst && includeBoxes) found.addAll(tracker.searchShulkerBoxWithItem(item, origin, range));
        return new ArrayList<>(found);
    }
}
