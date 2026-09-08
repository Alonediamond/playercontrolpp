package com.alonediamond.playercontrolpp.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;

import java.util.Collections;
import java.util.List;

/**
 * Litematica 联动的默认实现（stub）。
 *
 * <p>真正的方法体在 {@code mixin/compat/litematica/LitematicaIntegrationImpl} 里：装了
 * Litematica 时 MixinPlugin 放行注入，把这里的默认值整体覆写成直连实现；没装时本类
 * 按下面的默认值运行。stub 里不含任何 Litematica 类引用，随时可以安全加载。
 */
public class LitematicaIntegration {

    private static final LitematicaIntegration INSTANCE = new LitematicaIntegration();

    private LitematicaIntegration() {}

    public static LitematicaIntegration getInstance() { return INSTANCE; }

    /** Mixin 注入成功时覆写为 {@code true}；未注入即联动未生效。 */
    public boolean isLoaded() { return false; }

    /** 一个投影 placement 的世界坐标外框。 */
    public record PlacementBounds(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + sizeX
                    && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + sizeY
                    && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + sizeZ;
        }
    }

    /** Litematica 当前区域选区里一个盒子的世界坐标范围（闭区间）。 */
    public record SelectionBounds(BlockPos min, BlockPos max) {
        public long volume() {
            long sizeX = (long) max.getX() - min.getX() + 1L;
            long sizeY = (long) max.getY() - min.getY() + 1L;
            long sizeZ = (long) max.getZ() - min.getZ() + 1L;
            try {
                return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    /**
     * 投影材料清单里的一条：物品堆、需求总量、按 Litematica 口径的当前持有量，
     * 以及是否被清单方自己的「忽略」功能排除。
     */
    public record MaterialEntry(ItemStack stack, int countMissing, int countAvailable, boolean ignored) {}

    /** @return 是否至少加载了一个投影 placement。 */
    public boolean isSchematicLoaded() { return false; }

    /**
     * @return Litematica 的投影世界（一个 {@link BlockGetter}），用来读投影方块状态；
     *         没加载投影时 {@code null}。
     */
    public BlockGetter getSchematicWorld() { return null; }

    /**
     * @return 所有已加载 placement 的世界坐标范围。Litematica 不在、没加载投影、
     *         或它的内部结构不符合预期时返回空。
     */
    public List<PlacementBounds> getPlacementBounds() { return Collections.emptyList(); }

    /** @return Litematica 当前区域选区的所有盒子；没有则空列表。 */
    public List<SelectionBounds> getCurrentSelectionBounds() { return Collections.emptyList(); }

    /**
     * @return Litematica 当前的 MaterialList 原始对象；调用方用它做「是否被
     *         LitematList 接管」的类名判断，没有则 {@code null}。
     */
    public Object getMaterialList() { return null; }

    /** @return 投影材料清单的信息 HUD 是否正在渲染（清单只在这个前提下才被维护）。 */
    public boolean isMaterialListHudVisible() { return false; }

    /**
     * 读投影材料清单的条目，读之前会让 Litematica 对着真实物品栏刷新持有量。
     *
     * @return 条目列表；Litematica 不在或读取失败时为空列表
     */
    public List<MaterialEntry> getMaterialListEntries(Player player) { return Collections.emptyList(); }

    /** 移动渲染层，行为与 Litematica 自己的 PageUp/PageDown 热键一致。 */
    public boolean incrementLayer(int amount) { return false; }
}
