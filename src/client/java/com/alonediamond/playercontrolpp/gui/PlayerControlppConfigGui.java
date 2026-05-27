package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerControlppConfigGui extends GuiConfigsBase {

    public static final String MOD_ID = "playercontrolpp";

    private static ConfigGuiTab selectedTab = ConfigGuiTab.HOTKEYS;

    public PlayerControlppConfigGui(Screen parent) {
        super(10, 50, MOD_ID, parent, "playercontrolpp.gui.title");
    }

    @Override
    public void initGui() {
        super.initGui();
        clearOptions();

        int x = 10;
        int y = 26;
        for (ConfigGuiTab tab : ConfigGuiTab.values()) {
            int width = getStringWidth(tab.getDisplayName()) + 10;
            ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getDisplayName());
            ButtonListener listener = new ButtonListener(tab, this);
            addButton(button, listener);
            x += width + 2;
        }
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        switch (selectedTab) {
            case HOTKEYS:
                return ConfigOptionWrapper.createFor(Configs.Hotkeys.HOTKEY_LIST);
            case ROUTE_HOTKEYS:
                return ConfigOptionWrapper.createFor(
                        new ArrayList<>(RouteManager.getInstance().getRouteHotkeyList()));
            case SETTINGS:
                return ConfigOptionWrapper.createFor(Configs.Settings.OPTIONS);
            case ROUTES:
                return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    @Override
    protected boolean useKeybindSearch() {
        return selectedTab == ConfigGuiTab.HOTKEYS || selectedTab == ConfigGuiTab.ROUTE_HOTKEYS;
    }

    public enum ConfigGuiTab {
        HOTKEYS("playercontrolpp.gui.tab.hotkeys"),
        ROUTE_HOTKEYS("playercontrolpp.gui.tab.route_hotkeys"),
        SETTINGS("playercontrolpp.gui.tab.settings"),
        ROUTES("playercontrolpp.gui.tab.routes");

        private final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getDisplayName() {
            return StringUtils.translate(translationKey);
        }
    }

    private record ButtonListener(ConfigGuiTab tab, PlayerControlppConfigGui parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            if (tab == ConfigGuiTab.ROUTES) {
                MinecraftClient.getInstance().setScreen(new RouteListGui(parent));
            } else {
                selectedTab = tab;
                parent.getListWidget().refreshEntries();
            }
        }
    }
}
