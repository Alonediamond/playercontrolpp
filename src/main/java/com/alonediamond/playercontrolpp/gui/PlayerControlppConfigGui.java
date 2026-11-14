package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.DrawCtx;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import fi.dy.masa.malilib.config.IConfigBase;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerControlppConfigGui extends GuiConfigsBase {

    public static final String MOD_ID = "playercontrolpp";

    private static ConfigGuiTab selectedTab = ConfigGuiTab.HOTKEYS;
    private int listTop = 52;
    private boolean initialized;

    public PlayerControlppConfigGui(Screen parent) {
        super(10, 98, MOD_ID, parent, "playercontrolpp.gui.title");
    }

    @Override
    public void initGui() {
        if (selectedTab.requiresBaritone() && !baritoneAvailable()) {
            selectedTab = ConfigGuiTab.HOTKEYS;
        }
        List<TabButton> tabs = layoutTabs();
        listTop = tabs.isEmpty() ? 52 : tabs.getLast().y() + 26;
        setListPosition(10, listTop);
        if (initialized) {
            saveCurrentList();
            reCreateListWidget();
        }
        clearOptions();
        super.initGui();
        initialized = true;

        for (TabButton tab : tabs) {
            ButtonGeneric button = new ButtonGeneric(tab.x(), tab.y(), tab.width(), 20,
                    tab.tab().getDisplayName());
            addButton(button, new ButtonListener(tab.tab(), this));
        }
    }

    private List<TabButton> layoutTabs() {
        List<TabButton> tabs = new ArrayList<>();
        int x = 10;
        int y = 26;
        for (ConfigGuiTab tab : ConfigGuiTab.values()) {
            if (tab.requiresBaritone() && !baritoneAvailable()) {
                continue;
            }
            int width = getStringWidth(tab.getDisplayName()) + 10;
            if (x + width > this.width - 10 && x > 10) {
                x = 10;
                y += 22;
            }
            tabs.add(new TabButton(tab, x, y, width));
            x += width + 2;
        }
        return tabs;
    }

    private void saveCurrentList() {
        WidgetListConfigOptions list = getListWidget();
        list.applyPendingModifications();
        if (list.wereConfigsModified()) {
            onSettingsChanged();
            list.clearConfigsModifiedFlag();
        }
    }

    private void switchTab(ConfigGuiTab tab) {
        if (selectedTab == tab) return;
        saveCurrentList();
        clearOptions();
        selectedTab = tab;
        reCreateListWidget();
        getListWidget().initGui();
    }

    void restartSearch() {
        saveCurrentList();
        clearOptions();
        reCreateListWidget();
        getListWidget().initGui();
    }

    @Override
    protected int getBrowserHeight() {
        return Math.max(0, this.height - listTop - 10);
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int listX, int listY) {
        return new PlayerControlppConfigList(listX, listY, getBrowserWidth(), getBrowserHeight(),
                getConfigWidth(), useKeybindSearch(), this);
    }

    private record TabButton(ConfigGuiTab tab, int x, int y, int width) {}

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        if (selectedTab.requiresBaritone() && !baritoneAvailable()) {
            return Collections.emptyList();
        }
        switch (selectedTab) {
            case HOTKEYS:
                // 自动化热键在各自页面内，主要功能页保持简洁；未装 Baritone 时完全不可见。
                List<IConfigBase> hotkeyItems = new ArrayList<>();
                hotkeyItems.add(Configs.Hotkeys.OPEN_CONFIG_GUI);
                hotkeyItems.add(Configs.Hotkeys.AUTO_FORWARD);
                hotkeyItems.add(Configs.Hotkeys.QUICK_TURN);
                hotkeyItems.add(Configs.Hotkeys.RECORDING_TOGGLE);
                hotkeyItems.add(Configs.Hotkeys.AUTO_CACHE_NEARBY_CONTAINERS);
                hotkeyItems.add(Configs.Hotkeys.CACHE_SCHEMATIC_SELECTION_CONTAINERS);
                hotkeyItems.add(Configs.Hotkeys.WATER_FILL_TOGGLE);
                return ConfigOptionWrapper.createFor(hotkeyItems);
            case ROUTE_HOTKEYS:
                return ConfigOptionWrapper.createFor(
                        new ArrayList<>(RouteManager.getInstance().getRouteHotkeyList()));
            case SETTINGS:
                List<IConfigBase> settings = new ArrayList<>(Configs.Settings.OPTIONS);
                settings.addAll(Configs.CacheNearbySettings.OPTIONS);
                replaceOption(settings, Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_TICKS,
                        Configs.CacheNearbySettings.UNCACHED_HIGHLIGHT_SECONDS);
                return ConfigOptionWrapper.createFor(settings);
            case GATHER:
                return ConfigOptionWrapper.createFor(List.of(
                        Configs.Hotkeys.BARITONE_AUTO_GATHER,
                        Configs.BaritoneSettings.MATERIAL_LIST_SOURCE,
                        Configs.BaritoneSettings.CLICK_COOLDOWN,
                        Configs.BaritoneSettings.INTRA_CONTAINER_CLICK_DELAY,
                        Configs.BaritoneSettings.CONTAINER_CLOSE_DELAY,
                        Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD,
                        Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX,
                        Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER,
                        Configs.BaritoneSettings.SHULKER_STORAGE_MODE,
                        Configs.BaritoneSettings.SHULKER_PLACE_RADIUS,
                        Configs.BaritoneSettings.ALLOW_NUDGE_FOR_PLACEMENT,
                        Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE,
                        Configs.BaritoneSettings.GLOBAL_IGNORE_LIST,
                        Configs.BaritoneSettings.GATHER_TARGET_HIGHLIGHT,
                        Configs.BaritoneSettings.GATHER_TARGET_COLOR,
                        Configs.BaritoneSettings.GATHER_TARGET_OPACITY));
            case CRAFTING:
                return ConfigOptionWrapper.createFor(List.of(
                        Configs.BaritoneSettings.RAW_MATERIAL_CRAFT_MODE,
                        Configs.BaritoneSettings.RAW_MATERIAL_MAX_DEPTH,
                        Configs.BaritoneSettings.MINING_STALL_SECONDS,
                        Configs.BaritoneSettings.MINING_SEARCH_RADIUS,
                        Configs.BaritoneSettings.MINING_ATTEMPT_SECONDS,
                        Configs.BaritoneSettings.CRAFT_STATION_SOURCE,
                        Configs.Hotkeys.MARK_CRAFTING_TABLE,
                        Configs.BaritoneSettings.CRAFTING_TABLE_POSITIONS,
                        Configs.Hotkeys.MARK_STONECUTTER,
                        Configs.BaritoneSettings.STONECUTTER_POSITIONS));
            case RESTOCK:
                List<IConfigBase> restockOptions = new ArrayList<>();
                restockOptions.add(Configs.Hotkeys.ONE_CLICK_BUILD_RESTOCK);
                restockOptions.add(Configs.Hotkeys.MARK_CONTAINER);
                restockOptions.addAll(Configs.Restocks.OPTIONS);
                replaceOption(restockOptions, Configs.Restocks.BUILD_LAYER_STABLE_TICKS,
                        Configs.Restocks.BUILD_LAYER_STABLE_SECONDS);
                return ConfigOptionWrapper.createFor(restockOptions);
            case MATERIAL_QUEUE:
                return Collections.emptyList();
            case ROUTES:
                return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private static void replaceOption(List<IConfigBase> options, IConfigBase stored, IConfigBase displayed) {
        int index = options.indexOf(stored);
        if (index >= 0) options.set(index, displayed);
    }

    //#if MC >= 260000
    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        renderOverlay(new DrawCtx(drawContext));
    }
    //#else
    //$$ @Override
    //$$ public void render(net.minecraft.client.gui.GuiGraphics drawContext, int mouseX, int mouseY, float delta) {
    //$$     super.render(drawContext, mouseX, mouseY, delta);
    //$$     renderOverlay(new DrawCtx(drawContext));
    //$$ }
    //#endif

    /** render 里版本无关的部分：自动化页面顶部的红色警示。 */
    private void renderOverlay(DrawCtx drawContext) {
        if (selectedTab.requiresBaritone() && AutoMaterialGatherer.areAllThreeModsPresent()) {
            String warning = StringUtils.translate("playercontrolpp.gui.baritone.warning");
            int textWidth = font.width(warning);
            int x = (this.width - textWidth) / 2;
            drawContext.text(font,
                    Component.nullToEmpty(warning).copy().withStyle(ChatFormatting.RED),
                    x, 5, 0xFFFF0000,true);
        }
    }

    @Override
    protected boolean useKeybindSearch() {
        return selectedTab == ConfigGuiTab.HOTKEYS || selectedTab == ConfigGuiTab.ROUTE_HOTKEYS
                || selectedTab == ConfigGuiTab.GATHER || selectedTab == ConfigGuiTab.CRAFTING
                || selectedTab == ConfigGuiTab.RESTOCK;
    }

    public enum ConfigGuiTab {
        HOTKEYS("playercontrolpp.gui.tab.hotkeys"),
        ROUTE_HOTKEYS("playercontrolpp.gui.tab.route_hotkeys"),
        SETTINGS("playercontrolpp.gui.tab.settings"),
        ROUTES("playercontrolpp.gui.tab.routes"),
        RECORDING("playercontrolpp.gui.tab.recording"),
        GATHER("playercontrolpp.gui.tab.gather"),
        CRAFTING("playercontrolpp.gui.tab.crafting"),
        RESTOCK("playercontrolpp.gui.tab.restock"),
        MATERIAL_QUEUE("playercontrolpp.gui.tab.material_queue");

        private final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getDisplayName() {
            return StringUtils.translate(translationKey);
        }

        public boolean requiresBaritone() {
            return this == GATHER || this == CRAFTING || this == RESTOCK || this == MATERIAL_QUEUE;
        }
    }

    private static boolean baritoneAvailable() {
        return BaritoneIntegration.getInstance().isLoaded();
    }

    private record ButtonListener(ConfigGuiTab tab, PlayerControlppConfigGui parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            if (tab == ConfigGuiTab.ROUTES) {
                ScreenCompat.setScreen(Minecraft.getInstance(), new RouteListGui(parent));
            } else if (tab == ConfigGuiTab.RECORDING) {
                ScreenCompat.setScreen(Minecraft.getInstance(), new RecordingListGui(parent));
            } else if (tab == ConfigGuiTab.MATERIAL_QUEUE) {
                ScreenCompat.setScreen(Minecraft.getInstance(), new ManualMaterialQueueGui(parent));
            } else {
                parent.switchTab(tab);
            }
        }
    }
}
