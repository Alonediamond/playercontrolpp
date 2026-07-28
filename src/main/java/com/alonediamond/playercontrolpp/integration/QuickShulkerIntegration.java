package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * QuickShulker integration via reflection.
 * Calls QuickShulker's own {@code OpenShulkerPacket.sendOpenPacket(int)}
 * to open a shulker box directly from inventory.
 */
public class QuickShulkerIntegration implements ModIntegration {

    /**
     * First hotbar slot in {@code InventoryMenu} screen space. Vanilla exposes this as
     * {@code InventoryMenu.USE_ROW_SLOT_START}, but this project builds one source tree against
     * five Minecraft versions, so the constant lives here for the same reason
     * {@link PlayerUtil#HOTBAR_SIZE} does.
     */
    private static final int MENU_HOTBAR_START = 36;

    /** Offhand slot in {@code InventoryMenu} screen space ({@code InventoryMenu.SHIELD_SLOT}). */
    public static final int MENU_OFFHAND_SLOT = 45;

    private static final QuickShulkerIntegration INSTANCE = new QuickShulkerIntegration();
    private boolean loaded;

    private QuickShulkerIntegration() {}

    public static QuickShulkerIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("quickshulker");
    }

    /**
     * Translate an {@link Inventory} index into the {@code InventoryMenu} screen slot QuickShulker
     * expects.
     *
     * <p>The two numbering schemes agree for the main inventory and disagree for the hotbar:
     * <pre>
     * inventory 0-8   (hotbar)         → menu 36-44
     * inventory 9-35  (main inventory) → menu  9-35
     * </pre>
     * Passing a raw inventory index therefore works by accident for the main inventory and lands
     * on the crafting-result, crafting-grid and armour slots for the hotbar — which is why
     * shulker boxes carried on the hotbar silently failed to open. QuickShulker itself does the
     * same translation for the held item ({@code 36 + getSelectedSlot()}).
     *
     * @param inventorySlot slot index in {@link Inventory} space, 0-35
     * @return the matching {@code InventoryMenu} slot index
     */
    public static int menuSlotForInventorySlot(int inventorySlot) {
        return inventorySlot < PlayerUtil.HOTBAR_SIZE
                ? MENU_HOTBAR_START + inventorySlot
                : inventorySlot;
    }

    /**
     * @return whether QuickShulker is able to open this stack.
     *
     * <p>Shulker boxes register without {@code ignoreSingleStackCheck}, so QuickShulker only
     * opens a <em>single</em> box: its own client path gates on {@code getCount() <= 1}, and the
     * server-side listener force-closes the screen as soon as the slot holds a count other than
     * one. Trying to open a stack of two or more boxes looks like an open that immediately
     * dies, so the caller is better off skipping it.
     */
    public static boolean isOpenableShulkerBox(ItemStack stack) {
        return ItemUtil.isShulkerBox(stack) && stack.getCount() == 1;
    }

    /**
     * Open a shulker box via QuickShulker's own packet sender.
     *
     * <p>The server resolves the slot against {@code player.containerMenu}, so this is only
     * meaningful while the player's own inventory menu is the open one — with a chest open the
     * same index points at an entirely different slot. {@link #canOpenFromInventory(Minecraft)}
     * checks that precondition.
     *
     * @param menuSlot slot index in {@code InventoryMenu} screen space — use
     *                 {@link #menuSlotForInventorySlot(int)} to convert from an inventory index
     * @return false only if QuickShulker is not loaded or reflection fails
     */
    public boolean openShulkerBox(int menuSlot) {
        if (!loaded) return false;

        try {
            // OpenShulkerPacket.sendOpenPacket(menuSlot)
            Class<?> packetClass = Class.forName(
                    "net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            packetClass.getMethod("sendOpenPacket", int.class).invoke(null, menuSlot);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return whether an inventory-space slot index would be resolved correctly right now, i.e.
     *         the player's own inventory menu is the currently open container.
     */
    public boolean canOpenFromInventory(Minecraft mc) {
        return loaded && mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }
}
