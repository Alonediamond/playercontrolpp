package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 自动合成去哪里拿工作台 / 切石机。
 */
public enum CraftStationSource implements IConfigOptionListEntry {
    /** 先试物品栏（QuickShulker），不行再用记录的世界坐标。 */
    AUTO("playercontrolpp.config.baritone.craft_station.auto",
            "playercontrolpp.config.baritone.craft_station.auto.display_name"),
    /** 只用物品栏里的工作站（需要 QuickShulker）。 */
    INVENTORY("playercontrolpp.config.baritone.craft_station.inventory",
            "playercontrolpp.config.baritone.craft_station.inventory.display_name"),
    /** 只寻路到记录的世界坐标（不需要 QuickShulker）。 */
    WORLD("playercontrolpp.config.baritone.craft_station.world",
            "playercontrolpp.config.baritone.craft_station.world.display_name");

    private final String configKey;
    private final String translationKey;

    CraftStationSource(String configKey, String translationKey) {
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
        for (CraftStationSource source : values()) {
            if (source.configKey.equals(value)) return source;
        }
        return AUTO;
    }
}
