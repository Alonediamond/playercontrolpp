package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import fi.dy.masa.malilib.config.IConfigBase;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
            // Only show Baritone tab if all 3 mods are present
            if (tab == ConfigGuiTab.BARITONE && !AutoMaterialGatherer.areAllThreeModsPresent()) {
                continue;
            }
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
                List<IConfigBase> hotkeyItems = new ArrayList<>(Configs.Hotkeys.HOTKEY_LIST);
                hotkeyItems.add(Configs.CacheNearbySettings.CONTAINER_WHITELIST);
                return ConfigOptionWrapper.createFor(hotkeyItems);
            case ROUTE_HOTKEYS:
                return ConfigOptionWrapper.createFor(
                        new ArrayList<>(RouteManager.getInstance().getRouteHotkeyList()));
            case SETTINGS:
                return ConfigOptionWrapper.createFor(Configs.Settings.OPTIONS);
            case BARITONE:
                List<IConfigBase> baritoneOptions = new ArrayList<>();
                baritoneOptions.add(Configs.Hotkeys.BARITONE_AUTO_GATHER);
                for (IConfigBase opt : Configs.BaritoneSettings.OPTIONS) {
                    // Only show shulker storage mode when QuickShulker is installed
                    if (opt == Configs.BaritoneSettings.SHULKER_STORAGE_MODE) {
                        if (!QuickShulkerIntegration.getInstance().isLoaded()) continue;
                    }
                    baritoneOptions.add(opt);
                }
                return ConfigOptionWrapper.createFor(baritoneOptions);
            case ROUTES:
                return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext drawContext, int mouseX, int mouseY, float delta) {
        super.render(drawContext, mouseX, mouseY, delta);
        // Show red warning text on Baritone tab
        if (selectedTab == ConfigGuiTab.BARITONE && AutoMaterialGatherer.areAllThreeModsPresent()) {
            String warning = StringUtils.translate("playercontrolpp.gui.baritone.warning");
            int textWidth = textRenderer.getWidth(warning);
            int x = (this.width - textWidth) / 2;
            drawContext.drawTextWithShadow(textRenderer,
                    Text.of(warning).copy().formatted(Formatting.RED),
                    x, 5, 0xFFFF0000);
        }
    }

    @Override
    protected boolean useKeybindSearch() {
        return selectedTab == ConfigGuiTab.HOTKEYS || selectedTab == ConfigGuiTab.ROUTE_HOTKEYS
                || selectedTab == ConfigGuiTab.BARITONE;
    }

    public enum ConfigGuiTab {
        HOTKEYS("playercontrolpp.gui.tab.hotkeys"),
        ROUTE_HOTKEYS("playercontrolpp.gui.tab.route_hotkeys"),
        SETTINGS("playercontrolpp.gui.tab.settings"),
        ROUTES("playercontrolpp.gui.tab.routes"),
        RECORDING("playercontrolpp.gui.tab.recording"),
        BARITONE("playercontrolpp.gui.tab.baritone");

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
            } else if (tab == ConfigGuiTab.RECORDING) {
                MinecraftClient.getInstance().setScreen(new RecordingListGui(parent));
            } else {
                selectedTab = tab;
                parent.getListWidget().refreshEntries();
            }
        }
    }
}
