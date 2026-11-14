package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.util.PinyinTextSearch;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

/** 仅扩展本模组配置列表的文本匹配，保留 malilib 的热键过滤逻辑。 */
final class PlayerControlppConfigList extends WidgetListConfigOptions {
    private final PlayerControlppConfigGui configGui;
    private boolean searchWasOpen;

    PlayerControlppConfigList(int x, int y, int width, int height, int configWidth,
                              boolean useKeybindSearch, PlayerControlppConfigGui parent) {
        super(x, y, width, height, configWidth, 0.f, useKeybindSearch, parent);
        this.configGui = parent;
    }

    @Override
    protected void refreshBrowserEntries() {
        boolean searchOpen = widgetSearchBar != null && widgetSearchBar.isSearchOpen();
        if (searchWasOpen && !searchOpen) {
            // malilib 关闭搜索栏时保留文字与热键；重新建表才会同时清空两者。
            configGui.restartSearch();
            return;
        }
        searchWasOpen = searchOpen;
        super.refreshBrowserEntries();
    }

    @Override
    protected boolean entryMatchesFilter(ConfigOptionWrapper entry, String filterText) {
        if (super.entryMatchesFilter(entry, filterText)) return true;
        IConfigBase config = entry.getConfig();
        return config != null && PinyinTextSearch.matches(filterText,
                config.getConfigGuiDisplayName(), config.getName());
    }
}
