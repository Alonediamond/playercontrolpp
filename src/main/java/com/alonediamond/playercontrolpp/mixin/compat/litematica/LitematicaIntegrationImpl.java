package com.alonediamond.playercontrolpp.mixin.compat.litematica;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.PlayerCompat;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.PlacementBounds;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.SelectionBounds;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.StringUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link LitematicaIntegration} 的直连实现，由 MixinPlugin 在 Litematica 在场时注入，
 * 覆写 stub 的全部默认值。
 *
 * <p>Litematica 从 0.19.60（1.21.1）到 0.28.4（26.2）之间，这里用到的每个类名、方法名与
 * 签名都逐版本核对过（javap），没有任何版本分支。malilib 的 {@code LayerRange} 在新版本
 * 挪了包，但旧包名至今保留为兼容别名，直接 import 旧包全版本可用。
 *
 * <p>方法体统一捕获 {@link Throwable} 而不是 {@link Exception}：第三方构建（fork、
 * 未发布的改版）若让某个签名漂移，直接调用会抛 {@link NoSuchMethodError} 这类
 * {@code Error}，行为要与旧反射实现一样静默降级而不是炸掉 tick 循环。
 */
@Mixin(LitematicaIntegration.class)
public abstract class LitematicaIntegrationImpl {

    @Unique
    private static List<SchematicPlacement> allPlacements() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        return manager == null ? List.of() : manager.getAllSchematicsPlacements();
    }

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    @Overwrite(remap = false)
    public boolean isSchematicLoaded() {
        try {
            return !allPlacements().isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public BlockGetter getSchematicWorld() {
        try {
            return SchematicWorldHandler.getSchematicWorld();
        } catch (Throwable e) {
            return null;
        }
    }

    @Overwrite(remap = false)
    public List<PlacementBounds> getPlacementBounds() {
        List<SchematicPlacement> placements;
        try {
            placements = allPlacements();
        } catch (Throwable e) {
            return List.of();
        }

        List<PlacementBounds> result = new ArrayList<>(placements.size());
        for (SchematicPlacement placement : placements) {
            // 一个畸形的 placement 不该把其余的一起抹掉。
            BlockPos origin = placement.getOrigin();
            LitematicaSchematic schematic = placement.getSchematic();
            if (origin == null || schematic == null) continue;
            Vec3i size = schematic.getTotalSize();
            if (size == null) continue;
            result.add(new PlacementBounds(origin, size.getX(), size.getY(), size.getZ()));
        }
        return result;
    }

    /**
     * 读出 Litematica 当前区域选区的所有盒子。
     *
     * <p>刻意不用投影 placement 的范围：容器缓存功能操作的是玩家当前的区域选区，
     * 读的是选区内真实世界的方块，与 Litematica Printer 的 {@code Printer.siftBlock()} 一致。
     */
    @Overwrite(remap = false)
    public List<SelectionBounds> getCurrentSelectionBounds() {
        try {
            SelectionManager manager = DataManager.getSelectionManager();
            AreaSelection selection = manager == null ? null : manager.getCurrentSelection();
            if (selection == null) return List.of();

            List<Box> boxes = selection.getAllSubRegionBoxes();
            List<SelectionBounds> result = new ArrayList<>(boxes.size());
            for (Box box : boxes) {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                if (pos1 == null || pos2 == null) continue;

                result.add(new SelectionBounds(
                        new BlockPos(
                                Math.min(pos1.getX(), pos2.getX()),
                                Math.min(pos1.getY(), pos2.getY()),
                                Math.min(pos1.getZ(), pos2.getZ())),
                        new BlockPos(
                                Math.max(pos1.getX(), pos2.getX()),
                                Math.max(pos1.getY(), pos2.getY()),
                                Math.max(pos1.getZ(), pos2.getZ()))));
            }
            return result;
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to read Litematica's current area selection", e);
            return List.of();
        }
    }

    @Overwrite(remap = false)
    public Object getMaterialList() {
        try {
            return DataManager.getMaterialList();
        } catch (Throwable e) {
            return null;
        }
    }

    @Overwrite(remap = false)
    public boolean isMaterialListHudVisible() {
        try {
            if (DataManager.getMaterialList() instanceof MaterialListBase list) {
                return list.getHudRenderer().getShouldRenderCustom();
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    @Overwrite(remap = false)
    public List<LitematicaIntegration.MaterialEntry> getMaterialListEntries(Player player) {
        try {
            if (!(DataManager.getMaterialList() instanceof MaterialListBase list)) {
                return List.of();
            }

            List<MaterialListEntry> all = list.getMaterialsAll();
            if (all.isEmpty()) return List.of();

            // 对着真实物品栏重新数一遍持有量；缓存里的计数可能是过期的。
            if (player != null) {
                MaterialListUtils.updateAvailableCounts(all, player);
            }

            Set<?> ignored = ((MaterialListBaseAccessor) (Object) list).litematica$getIgnoredSet();

            List<LitematicaIntegration.MaterialEntry> result = new ArrayList<>(all.size());
            for (MaterialListEntry entry : all) {
                ItemStack stack = entry.getStack();
                if (stack == null) continue;
                result.add(new LitematicaIntegration.MaterialEntry(
                        stack, entry.getCountMissing(), entry.getCountAvailable(), ignored.contains(entry)));
            }
            return result;
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to read the Litematica material list", e);
            return List.of();
        }
    }

    @Overwrite(remap = false)
    public boolean incrementLayer(int amount) {
        if (amount == 0) return false;

        try {
            // malilib 0.28 起该返回类型挪了包（util.LayerRange → util.position.LayerRange），
            // 用 var 让各子项目按自己依赖的 malilib 推断，源码不需要分支。
            var range = DataManager.getRenderLayerRange();
            if (range == null) return false;
            if (range.getLayerMode() != LayerMode.SINGLE_LAYER) return false;
            if (!range.moveLayer(amount)) return false;

            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                PlayerCompat.sendOverlayMessage(client.player,
                        StringUtils.translateAsText("playercontrolpp.message.litematica.layer",
                                range.getCurrentLayerString()));
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
