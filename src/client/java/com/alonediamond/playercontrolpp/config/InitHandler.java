package com.alonediamond.playercontrolpp.config;

import com.alonediamond.playercontrolpp.event.ClientEventHandler;
import com.alonediamond.playercontrolpp.input.KeybindCallbacks;
import com.alonediamond.playercontrolpp.input.KeybindProvider;
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
    }

    public static void register() {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
