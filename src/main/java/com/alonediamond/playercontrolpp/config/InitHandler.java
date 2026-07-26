package com.alonediamond.playercontrolpp.config;

import com.alonediamond.playercontrolpp.event.ClientEventHandler;
import com.alonediamond.playercontrolpp.input.KeybindCallbacks;
import com.alonediamond.playercontrolpp.input.KeybindProvider;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

public class InitHandler implements IInitializationHandler {

    public static final String MOD_ID = "playercontrolpp";

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(MOD_ID, new Configs());
        InputEventHandler.getKeybindManager().registerKeybindProvider(new KeybindProvider());
        KeybindCallbacks.register();
        ClientEventHandler.register();
        Configs.loadFromFile();
        RouteManager.getInstance().loadRoutes();
        RouteManager.getInstance().registerAllKeybinds();
        RecordingManager.getInstance().loadRecordings();

        // Initialize optional integrations
        LitematicaIntegration.getInstance().initialize();
        BaritoneIntegration.getInstance().initialize();
        ChestTrackerIntegration.getInstance().initialize();
        QuickShulkerIntegration.getInstance().initialize();
    }

    public static void register() {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
