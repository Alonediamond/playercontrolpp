package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

import java.util.ArrayList;
import java.util.List;

public class KeybindProvider implements IKeybindProvider {

    private static final String MOD_NAME = "PlayerControl++";

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : Configs.Hotkeys.HOTKEY_LIST) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        for (IHotkey hotkey : RouteManager.getInstance().getRouteHotkeyList()) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        // Register Baritone hotkey if mods are present
        if (com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.areAllThreeModsPresent()) {
            manager.addKeybindToMap(Configs.Hotkeys.BARITONE_AUTO_GATHER.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<IHotkey> allHotkeys = new ArrayList<>(Configs.Hotkeys.HOTKEY_LIST);
        allHotkeys.addAll(RouteManager.getInstance().getRouteHotkeyList());
        manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.hotkeys", allHotkeys);
        // Register Baritone category if mods are present
        if (com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.areAllThreeModsPresent()) {
            List<IHotkey> baritoneKeys = List.of(Configs.Hotkeys.BARITONE_AUTO_GATHER);
            manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.baritone", baritoneKeys);
        }
    }
}
