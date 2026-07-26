package com.alonediamond.playercontrolpp.compat;

import net.minecraft.world.entity.player.Inventory;

/**
 * The selected hotbar slot.
 *
 * <p>Minecraft 1.21.5 encapsulated the public {@code Inventory.selected} field behind
 * {@code getSelectedSlot()} / {@code setSelectedSlot(int)}. The setter also gained an
 * {@code isHotbarSlot} range check, so callers must keep passing values in {@code 0..8}
 * on every version.
 */
public final class InventoryCompat {

    private InventoryCompat() {}

    /** @return the currently selected hotbar slot, {@code 0..8}. */
    public static int getSelectedSlot(Inventory inventory) {
        //#if MC >= 12105
        return inventory.getSelectedSlot();
        //#else
        //$$ return inventory.selected;
        //#endif
    }

    /**
     * Selects a hotbar slot client-side. Callers still have to sync the change to the
     * server with a {@code ServerboundSetCarriedItemPacket}.
     *
     * @param slot hotbar slot, must be in {@code 0..8}
     */
    public static void setSelectedSlot(Inventory inventory, int slot) {
        //#if MC >= 12105
        inventory.setSelectedSlot(slot);
        //#else
        //$$ inventory.selected = slot;
        //#endif
    }
}
