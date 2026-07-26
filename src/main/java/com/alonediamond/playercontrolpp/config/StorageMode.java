package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum StorageMode implements IConfigOptionListEntry {
    SIMULATE("playercontrolpp.config.baritone.storage_mode.simulate",
            "playercontrolpp.config.baritone.storage_mode.simulate.display_name"),
    QUICKSHULKER("playercontrolpp.config.baritone.storage_mode.quickshulker",
            "playercontrolpp.config.baritone.storage_mode.quickshulker.display_name");

    private final String configKey;
    private final String translationKey;

    StorageMode(String configKey, String translationKey) {
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
        for (StorageMode mode : values()) {
            if (mode.configKey.equals(value)) return mode;
        }
        return SIMULATE;
    }
}
