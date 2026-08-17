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
 * 告诉 malilib 本模组用了哪些键。路径热键每次调用都从 {@code RouteManager} 重新枚举，
 * 所以增删路径只需要 {@code updateUsedKeys()}——这是它们唯一的注册路径。
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

        // Baritone 分类只有在三个配套模组都装了时才有意义。
        if (AutoMaterialGatherer.areAllThreeModsPresent()) {
            manager.addHotkeysForCategory(MOD_NAME, "playercontrolpp.gui.tab.baritone",
                    List.of(Configs.Hotkeys.BARITONE_AUTO_GATHER));
        }
    }
}
