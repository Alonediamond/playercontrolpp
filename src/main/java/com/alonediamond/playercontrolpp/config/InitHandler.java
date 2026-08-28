package com.alonediamond.playercontrolpp.config;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.event.ClientEventHandler;
import com.alonediamond.playercontrolpp.feature.AutoCacheNearbyContainersFeature;
import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.AutoWaterFillFeature;
import com.alonediamond.playercontrolpp.feature.FeatureRegistry;
import com.alonediamond.playercontrolpp.feature.SchematicSelectionContainerCacheFeature;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoRestockFeature;
import com.alonediamond.playercontrolpp.input.KeybindCallbacks;
import com.alonediamond.playercontrolpp.input.KeybindProvider;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
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
        // 路径已经存在了，让 malilib 收录它们的热键。
        RouteManager.getInstance().refreshKeybinds();
        RecordingManager.getInstance().loadRecordings();

        // 可选联动：每个只记录对应模组在不在。
        LitematicaIntegration.getInstance().initialize();
        BaritoneIntegration.getInstance().initialize();
        ChestTrackerIntegration.getInstance().initialize();
        QuickShulkerIntegration.getInstance().initialize();
        LitematListIntegration.getInstance().initialize();
    }

    /**
     * 注册顺序就是 tick 顺序，也是世界切换的通知顺序。
     *
     * <p>路径与录制排在前面，因为它们产生的移动输入要由 {@code ClientEventHandler}
     * 在所有功能 tick 完之后读取；自动化类功能排在后面。这也是注册表出现之前这些功能的调用顺序。
     */
    private void registerFeatures() {
        FeatureRegistry.register(AutoForwardFeature.FEATURE);
        FeatureRegistry.register(RouteFlowRuntime.getInstance());
        FeatureRegistry.register(RecordingManager.getInstance());
        FeatureRegistry.register(AutoMaterialGatherer.getInstance());
        FeatureRegistry.register(AutoCacheNearbyContainersFeature.FEATURE);
        FeatureRegistry.register(SchematicSelectionContainerCacheFeature.FEATURE);
        FeatureRegistry.register(AutoWaterFillFeature.FEATURE);
        FeatureRegistry.register(AutoRestockFeature.getInstance());
    }

    public static void register() {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
