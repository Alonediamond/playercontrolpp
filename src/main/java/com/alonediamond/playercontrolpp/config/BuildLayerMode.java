package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/** 建造按当前 Y 层推进的方向。 */
public enum BuildLayerMode implements IConfigOptionListEntry {
    OFF("off", "playercontrolpp.config.baritone.build_layer_mode.off.display_name"),
    UP("up", "playercontrolpp.config.baritone.build_layer_mode.up.display_name"),
    DOWN("down", "playercontrolpp.config.baritone.build_layer_mode.down.display_name");

    private final String configKey;
    private final String translationKey;

    BuildLayerMode(String configKey, String translationKey) {
        this.configKey = configKey;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() { return configKey; }

    @Override
    public String getDisplayName() { return StringUtils.translate(translationKey); }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int next = ordinal() + (forward ? 1 : -1);
        if (next >= values().length) next = 0;
        if (next < 0) next = values().length - 1;
        return values()[next];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (BuildLayerMode mode : values()) {
            if (mode.configKey.equals(value)) return mode;
        }
        return OFF;
    }
}
