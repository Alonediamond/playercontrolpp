package com.alonediamond.playercontrolpp.feature.automaterial.restock;

import com.alonediamond.playercontrolpp.compat.WorldMarkScope;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Availability;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Mark;
import com.alonediamond.playercontrolpp.inventory.ContainerMenus;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 标记容器的热键、状态与续料候选。 */
public final class MarkedContainerManager {
    private static final MarkedContainerManager INSTANCE = new MarkedContainerManager();
    private final ScopedBlockMarks marks = new ScopedBlockMarks(Configs.Restocks.MARKED_CONTAINERS);

    private MarkedContainerManager() {}

    public static MarkedContainerManager getInstance() { return INSTANCE; }

    public ScopedBlockMarks marks() { return marks; }

    public Availability availability(Level level, BlockPos pos) {
        return ScopedBlockMarks.availability(level, pos, target -> ContainerMenus.isMarkedStorage(level, target));
    }

    public void mark(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        WorldMarkScope scope = WorldMarkScope.current(mc);
        if (scope == null) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.marks.requires_world");
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();

        if (mc.player.isShiftKeyDown()) {
            Boolean enabled = marks.toggleEnabled(scope, pos);
            if (enabled != null) {
                MessageUtil.sendActionBar(mc, enabled
                                ? "playercontrolpp.message.restock.container_enabled"
                                : "playercontrolpp.message.restock.container_disabled",
                        pos.getX(), pos.getY(), pos.getZ());
                return;
            }
        }

        if (marks.remove(scope, pos)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_unmarked",
                    pos.getX(), pos.getY(), pos.getZ());
            return;
        }
        if (availability(mc.level, pos) != Availability.VALID) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.mark_wrong_block");
            return;
        }
        marks.add(scope, pos);
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_marked",
                pos.getX(), pos.getY(), pos.getZ());
    }

    public Targets pickTargets(Minecraft mc) {
        WorldMarkScope scope = WorldMarkScope.current(mc);
        if (scope == null || mc.player == null) return new Targets(List.of(), 0, 0, 0);
        BlockPos origin = mc.player.blockPosition();
        int maxDistance = Configs.Restocks.RESTOCK_CONTAINER_MAX_DISTANCE.getIntegerValue();
        long maxDistanceSq = maxDistance <= 0 ? Long.MAX_VALUE : (long) maxDistance * maxDistance;

        List<BlockPos> usable = new ArrayList<>();
        int disabled = 0;
        int tooFar = 0;
        int invalid = 0;
        for (Mark mark : marks.entries(scope)) {
            if (!mark.enabled()) {
                disabled++;
            } else if (mark.pos().distSqr(origin) > maxDistanceSq) {
                tooFar++;
            } else if (availability(mc.level, mark.pos()) == Availability.INVALID) {
                invalid++;
            } else {
                usable.add(mark.pos());
            }
        }
        usable.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        return new Targets(usable, disabled, tooFar, invalid);
    }

    public record Targets(List<BlockPos> positions, int disabled, int tooFar, int invalid) {
        public boolean isEmpty() { return positions.isEmpty(); }
        public int filtered() { return disabled + tooFar + invalid; }
    }
}
