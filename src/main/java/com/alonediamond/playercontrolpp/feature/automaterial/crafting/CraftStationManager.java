package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.WorldMarkScope;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Availability;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Mark;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/** 工作台与切石机各有一份按世界和维度隔离的标记列表。 */
public final class CraftStationManager {
    public enum Kind {
        CRAFTING_TABLE(Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE,
                Configs.BaritoneSettings.CRAFTING_TABLE_POSITIONS, "crafting_table"),
        STONECUTTER(Items.STONECUTTER, Blocks.STONECUTTER,
                Configs.BaritoneSettings.STONECUTTER_POSITIONS, "stonecutter");

        public final Item item;
        public final Block block;
        private final ScopedBlockMarks marks;
        private final String messageKey;

        Kind(Item item, Block block, ConfigStringList config, String messageKey) {
            this.item = item;
            this.block = block;
            this.marks = new ScopedBlockMarks(config);
            this.messageKey = messageKey;
        }

        public ScopedBlockMarks marks() { return marks; }

        public Availability availability(Level level, BlockPos pos) {
            return ScopedBlockMarks.availability(level, pos, target -> level.getBlockState(target).is(block));
        }
    }

    private static final CraftStationManager INSTANCE = new CraftStationManager();

    private CraftStationManager() {}

    public static CraftStationManager getInstance() { return INSTANCE; }

    public void mark(Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        WorldMarkScope scope = WorldMarkScope.current(mc);
        if (scope == null) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.marks.requires_world");
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_look_at_block");
            return;
        }

        BlockPos pos = hit.getBlockPos();
        if (kind.marks.remove(scope, pos)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_unmarked_" + kind.messageKey,
                    pos.getX(), pos.getY(), pos.getZ());
            return;
        }
        if (kind.availability(mc.level, pos) != Availability.VALID) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_wrong_block",
                    BuiltInRegistries.BLOCK.getKey(kind.block).toString());
            return;
        }
        kind.marks.add(scope, pos);
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.craft_station_marked_" + kind.messageKey,
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** 未加载的候选可先寻路，待区块加载再核验。 */
    public BlockPos nearest(Minecraft mc, Kind kind) {
        WorldMarkScope scope = WorldMarkScope.current(mc);
        if (scope == null || mc.player == null) return null;
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        for (Mark mark : kind.marks.entries(scope)) {
            BlockPos pos = mark.pos();
            double candidate = pos.distSqr(origin);
            if (mark.enabled() && candidate < distance && kind.availability(mc.level, pos) != Availability.INVALID) {
                best = pos;
                distance = candidate;
            }
        }
        return best;
    }

    public boolean hasAvailable(Level level, WorldMarkScope scope, Kind kind) {
        return kind.marks.entries(scope).stream()
                .anyMatch(mark -> mark.enabled() && kind.availability(level, mark.pos()) != Availability.INVALID);
    }
}
