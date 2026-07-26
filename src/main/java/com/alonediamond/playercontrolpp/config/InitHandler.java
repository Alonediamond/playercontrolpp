package com.alonediamond.playercontrolpp.config;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.event.ClientEventHandler;
import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.AutoWaterFillFeature;
import com.alonediamond.playercontrolpp.feature.FeatureRegistry;
import com.alonediamond.playercontrolpp.input.KeybindCallbacks;
import com.alonediamond.playercontrolpp.input.KeybindProvider;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

public class InitHandler implements IInitializationHandler {

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(Playercontrolpp.MOD_ID, new Configs());
        InputEventHandler.getKeybindManager().registerKeybindProvider(new KeybindProvider());
        KeybindCallbacks.register();
        registerFeatures();
        ClientEventHandler.register();

        Configs.loadFromFile();
        RouteManager.getInstance().loadRoutes();
        // Routes exist now, so have malilib pick up their hotkeys.
        RouteManager.getInstance().refreshKeybinds();
        RecordingManager.getInstance().loadRecordings();

        // Optional integrations: each one just records whether its mod is present.
        LitematicaIntegration.getInstance().initialize();
        BaritoneIntegration.getInstance().initialize();
        ChestTrackerIntegration.getInstance().initialize();
        QuickShulkerIntegration.getInstance().initialize();
    }

    /**
     * Registration order is the tick order and the world-change order.
     *
     * <p>Routes and recordings come first because they produce the movement input that
     * {@code ClientEventHandler} reads after all features have ticked; the automation features
     * follow. This is the order these features were called in before the registry existed.
     */
    private void registerFeatures() {
        FeatureRegistry.register(AutoForwardFeature.FEATURE);
        FeatureRegistry.register(RouteFlowRuntime.getInstance());
        FeatureRegistry.register(RecordingManager.getInstance());
        FeatureRegistry.register(AutoMaterialGatherer.getInstance());
        FeatureRegistry.register(AutoCacheNearbyContainersFeature.FEATURE);
        FeatureRegistry.register(AutoWaterFillFeature.FEATURE);
    }

    public static void register() {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
