package com.alonediamond.playercontrolpp.integration;

import net.fabricmc.loader.api.FabricLoader;

/**
 * QuickShulker integration via reflection.
 * Calls QuickShulker's own {@code OpenShulkerPacket.sendOpenPacket(int)}
 * to open a shulker box directly from inventory.
 */
public class QuickShulkerIntegration implements ModIntegration {

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
     * Open a shulker box via QuickShulker's own packet sender.
     *
     * @param screenSlot slot index in the player's current ScreenHandler
     *                   (PlayerScreenHandler: 36-44 = hotbar, 9-35 = main inventory)
     * @return false only if QuickShulker is not loaded or reflection fails
     */
    public boolean openShulkerBox(int screenSlot) {
        if (!loaded) return false;

        try {
            // OpenShulkerPacket.sendOpenPacket(screenSlot)
            Class<?> packetClass = Class.forName(
                    "net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            packetClass.getMethod("sendOpenPacket", int.class).invoke(null, screenSlot);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
