package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/** 一键建造续料的容器位置来源。 */
public enum RestockContainerSource implements IConfigOptionListEntry {
    MARKED("marked", "playercontrolpp.config.baritone.restock_container_source.marked.display_name"),
    CHEST_TRACKER("chest_tracker", "playercontrolpp.config.baritone.restock_container_source.chest_tracker.display_name");

    private final String configKey;
    private final String translationKey;

    RestockContainerSource(String configKey, String translationKey) {
        this.configKey = configKey;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() { return configKey; }

    @Override
    public String getDisplayName() { return StringUtils.translate(translationKey); }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int next = this.ordinal() + (forward ? 1 : -1);
        if (next >= values().length) next = 0;
        if (next < 0) next = values().length - 1;
        return values()[next];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (RestockContainerSource source : values()) {
            if (source.configKey.equals(value)) return source;
        }
        return MARKED;
    }
}
