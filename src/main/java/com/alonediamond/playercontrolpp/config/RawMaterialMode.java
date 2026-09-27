package com.alonediamond.playercontrolpp.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 备货途中对"缓存里没有」的材料要不要追溯原材料并合成"的处理方式。
 *
 * <p>只在装了 LitematList 时才有意义：原材料树来自 LitematList 的配方溯源（连带继承玩家
 * 在那边配置的溯源白名单与配方优先级），因此三个取值里没有"跟随投影"的选项。
 */
public enum RawMaterialMode implements IConfigOptionListEntry {
    /** 维持 v1.8 行为：缓存里没有就直接跳过该材料。 */
    DISABLED("disabled", "playercontrolpp.config.baritone.raw_material_mode.disabled.display_name"),
    /** 只从箱子追踪缓存里的容器收集原材料，不离开既定容器路线。 */
    CACHE_ONLY("cache_only", "playercontrolpp.config.baritone.raw_material_mode.cache_only.display_name"),
    /** 缓存里找不到时，交给 Baritone 在世界里挖掘/寻找。 */
    WORLD_SEARCH("world_search", "playercontrolpp.config.baritone.raw_material_mode.world_search.display_name");

    private final String configKey;
    private final String translationKey;

    RawMaterialMode(String configKey, String translationKey) {
        this.configKey = configKey;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() { return this.configKey; }

    @Override
    public String getDisplayName() { return StringUtils.translate(this.translationKey); }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int next = this.ordinal() + (forward ? 1 : -1);
        if (next >= values().length) next = 0;
        if (next < 0) next = values().length - 1;
        return values()[next];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (RawMaterialMode mode : values()) {
            if (mode.configKey.equals(value)) return mode;
        }
        return DISABLED;
    }
}
