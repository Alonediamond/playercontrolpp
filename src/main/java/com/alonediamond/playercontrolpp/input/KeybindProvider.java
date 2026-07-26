package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells malilib which keys this mod uses. Route hotkeys are enumerated from
 * {@code RouteManager} on every call, so adding or deleting a route only needs
 * {@code updateUsedKeys()} — this is the single registration path for them.
 */
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
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<IHotkey> allHotkeys = new ArrayList<>(Configs.Hotkeys.HOTKEY_LIST);
        allHotkeys.addAll(RouteManager.getInstance().getRouteHotkeyList());
        manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.hotkeys", allHotkeys);

        // The Baritone category only makes sense with all three companion mods installed.
        if (AutoMaterialGatherer.areAllThreeModsPresent()) {
            manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.baritone",
                    List.of(Configs.Hotkeys.BARITONE_AUTO_GATHER));
        }
    }
}
