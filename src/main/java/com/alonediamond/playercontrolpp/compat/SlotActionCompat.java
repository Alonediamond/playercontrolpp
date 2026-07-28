package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.Minecraft;

//#if MC >= 260000
import net.minecraft.world.inventory.ContainerInput;
//#else
//$$ import net.minecraft.world.inventory.ClickType;
//#endif

/**
 * Sending a container slot click to the server.
 *
 * <p>Minecraft 26.1 renamed both halves of this API at once:
 * <ul>
 *   <li>{@code MultiPlayerGameMode.handleInventoryMouseClick} &rarr; {@code handleContainerInput}</li>
 *   <li>{@code ClickType} &rarr; {@code ContainerInput}</li>
 * </ul>
 * Because the method signature changed together with the enum, the source remapper
 * cannot bridge it automatically, so the whole call lives here.
 *
 * <p>All PlayerControl++ call sites use mouse button {@code 0} (left click).
 */
public final class SlotActionCompat {

    private SlotActionCompat() {}

    /**
     * Left-clicks {@code slotIndex}: picks up the stack under the cursor, or places
     * the carried stack into the slot.
     *
     * @param containerId the {@code containerId} of the currently open menu
     * @param slotIndex   the slot index <em>in screen space</em> (not inventory space)
     */
    public static void pickup(Minecraft mc, int containerId, int slotIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, 0, ContainerInput.PICKUP, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.PICKUP, mc.player);
        //#endif
    }

    /**
     * Shift-left-clicks {@code slotIndex}, moving the whole stack to the other inventory.
     *
     * @param containerId the {@code containerId} of the currently open menu
     * @param slotIndex   the slot index <em>in screen space</em> (not inventory space)
     */
    public static void quickMove(Minecraft mc, int containerId, int slotIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.QUICK_MOVE, mc.player);
        //#endif
    }

    /**
     * The number-key swap: exchanges the contents of {@code slotIndex} with hotbar slot
     * {@code hotbarIndex}, exactly as pressing 1-9 while hovering a slot does.
     *
     * <p>One packet instead of the two-click pickup/place pair, so the swap cannot be left
     * half-applied if the run is aborted between ticks.
     *
     * @param containerId the {@code containerId} of the currently open menu
     * @param slotIndex   the slot index <em>in screen space</em> (not inventory space)
     * @param hotbarIndex hotbar slot 0-8, in <em>inventory</em> space
     */
    public static void swapWithHotbar(Minecraft mc, int containerId, int slotIndex, int hotbarIndex) {
        //#if MC >= 260000
        mc.gameMode.handleContainerInput(containerId, slotIndex, hotbarIndex, ContainerInput.SWAP, mc.player);
        //#else
        //$$ mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, hotbarIndex, ClickType.SWAP, mc.player);
        //#endif
    }
}
