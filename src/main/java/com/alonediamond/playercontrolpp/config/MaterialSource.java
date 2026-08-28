package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 自动投影材料备货读取哪份材料清单：跟随投影自己的信息HUD，还是跟随 LitematList 上传的清单。
 */
public enum MaterialSource implements IConfigOptionListEntry {
    LITEMATICA("litematica", "playercontrolpp.config.baritone.material_source.litematica.display_name"),
    LITEMATLIST("litematlist", "playercontrolpp.config.baritone.material_source.litematlist.display_name");

    private final String configKey;
    private final String translationKey;

    MaterialSource(String configKey, String translationKey) {
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
        for (MaterialSource source : values()) {
            if (source.configKey.equals(value)) return source;
        }
        return LITEMATICA;
    }
}
