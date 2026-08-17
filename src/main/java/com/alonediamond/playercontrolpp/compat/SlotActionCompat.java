package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.Minecraft;

//#if MC >= 260000
import net.minecraft.world.inventory.ContainerInput;
//#else
//$$ import net.minecraft.world.inventory.ClickType;
//#endif

/**
 * 向服务端发送容器槽位点击。
 *
 * <p>26.1 把这套 API 的两半一起改了名：
 * <ul>
 *   <li>{@code MultiPlayerGameMode.handleInventoryMouseClick} &rarr; {@code handleContainerInput}</li>
 *   <li>{@code ClickType} &rarr; {@code ContainerInput}</li>
 * </ul>
 * 方法签名跟着枚举一起变，源码重映射器桥接不了，所以整个调用收在这里。
 *
 * <p>模组所有调用点都用鼠标键 {@code 0}（左键）。
 */
public final class SlotActionCompat {

    private SlotActionCompat() {}

    /**
     * 左键点 {@code slotIndex}：拿起该槽的物品，或把手上的物品放进去。
     *
     * @param containerId 当前打开菜单的 {@code containerId}
     * @param slotIndex   槽位索引，<em>界面空间</em>（不是物品栏空间）
     */
    public static void pickup(Minecraft mc, int containerId, int slotIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, 0, ContainerInput.PICKUP, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.PICKUP, mc.player);
        //#endif
    }

    /**
     * Shift + 左键点 {@code slotIndex}，整堆移到另一侧物品栏。
     *
     * @param containerId 当前打开菜单的 {@code containerId}
     * @param slotIndex   槽位索引，<em>界面空间</em>
     */
    public static void quickMove(Minecraft mc, int containerId, int slotIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.QUICK_MOVE, mc.player);
        //#endif
    }

    /**
     * 数字键交换：把 {@code slotIndex} 与快捷栏第 {@code hotbarIndex} 格互换，
     * 等价于鼠标悬停在槽位上按 1-9。
     *
     * <p>一个包搞定，不用"拿起 + 放下"两次点击，所以中途 tick 被中断也不会留下半个交换。
     *
     * @param containerId 当前打开菜单的 {@code containerId}
     * @param slotIndex   槽位索引，<em>界面空间</em>
     * @param hotbarIndex 快捷栏 0-8，<em>物品栏空间</em>
     */
    public static void swapWithHotbar(Minecraft mc, int containerId, int slotIndex, int hotbarIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, hotbarIndex, ContainerInput.SWAP, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, hotbarIndex, ClickType.SWAP, mc.player);
        //#endif
    }
}
