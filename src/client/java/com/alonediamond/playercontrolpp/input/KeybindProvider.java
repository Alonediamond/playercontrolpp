package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.config.Configs;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

public class KeybindProvider implements IKeybindProvider {

    private static final String MOD_NAME = "PlayerControl++";

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : Configs.Hotkeys.HOTKEY_LIST) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.hotkeys", Configs.Hotkeys.HOTKEY_LIST);
    }
}
