package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 等价原材料的变体挑选方式。
 */
public enum VariantMode implements IConfigOptionListEntry {
    CACHE_COUNT("playercontrolpp.config.baritone.variant_mode.cache_count",
            "playercontrolpp.config.baritone.variant_mode.cache_count.display_name"),
    TREE_DEFAULT("playercontrolpp.config.baritone.variant_mode.tree_default",
            "playercontrolpp.config.baritone.variant_mode.tree_default.display_name");

    private final String configKey;
    private final String translationKey;

    VariantMode(String configKey, String translationKey) {
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
        for (VariantMode mode : values()) {
            if (mode.configKey.equals(value)) return mode;
        }
        return CACHE_COUNT;
    }
}
