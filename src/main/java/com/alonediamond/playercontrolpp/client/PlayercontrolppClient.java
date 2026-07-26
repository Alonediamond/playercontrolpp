package com.alonediamond.playercontrolpp.client;

import com.alonediamond.playercontrolpp.config.InitHandler;
import net.fabricmc.api.ClientModInitializer;

public class PlayercontrolppClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        InitHandler.register();
    }
}
