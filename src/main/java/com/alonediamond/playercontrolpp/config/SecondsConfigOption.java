package com.alonediamond.playercontrolpp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;

/**
 * 仅供配置界面编辑秒数。磁盘列表仍持有原来的整数 tick 配置，因而旧 JSON 的数值无需迁移。
 * malilib 对 INTEGER 类型的输入框只允许整数；DOUBLE 类型才接受小数秒。
 */
public final class SecondsConfigOption extends ConfigDouble {
    private final ConfigInteger ticks;

    private SecondsConfigOption(ConfigInteger ticks) {
        super(ticks.getName(), ticks.getDefaultIntegerValue() / 20.0,
                ticks.getMinIntegerValue() / 20.0, ticks.getMaxIntegerValue() / 20.0,
                "Edit in seconds; stored as ticks.");
        this.ticks = ticks;
    }

    public static SecondsConfigOption forTicks(ConfigInteger ticks, String translationPrefix) {
        SecondsConfigOption option = new SecondsConfigOption(ticks);
        option.apply(translationPrefix);
        return option;
    }

    public ConfigInteger tickConfig() {
        return this.ticks;
    }

    @Override
    public double getDoubleValue() {
        return TickDuration.seconds(this.ticks.getIntegerValue());
    }

    @Override
    public void setDoubleValue(double seconds) {
        if (!Double.isFinite(seconds)) return;
        int oldTicks = this.ticks.getIntegerValue();
        this.ticks.setIntegerValue(TickDuration.toTicks(seconds,
                this.ticks.getMinIntegerValue(), this.ticks.getMaxIntegerValue()));
        if (oldTicks != this.ticks.getIntegerValue()) this.onValueChanged();
    }

    @Override
    public String getStringValue() {
        return TickDuration.formatSeconds(this.ticks.getIntegerValue());
    }

    @Override
    public String getDefaultStringValue() {
        return TickDuration.formatSeconds(this.ticks.getDefaultIntegerValue());
    }

    @Override
    public void setValueFromString(String seconds) {
        try {
            int oldTicks = this.ticks.getIntegerValue();
            this.ticks.setIntegerValue(TickDuration.parseSeconds(seconds,
                    this.ticks.getMinIntegerValue(), this.ticks.getMaxIntegerValue()));
            if (oldTicks != this.ticks.getIntegerValue()) this.onValueChanged();
        } catch (NumberFormatException ignored) {
            // 编辑过程中的不完整输入不改动已保存的 tick 值。
        }
    }

    @Override
    public boolean isModified() {
        return this.ticks.isModified();
    }

    @Override
    public boolean isModified(String seconds) {
        try {
            return TickDuration.parseSeconds(seconds,
                    this.ticks.getMinIntegerValue(), this.ticks.getMaxIntegerValue()) != this.ticks.getDefaultIntegerValue();
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    @Override
    public void resetToDefault() {
        int oldTicks = this.ticks.getIntegerValue();
        this.ticks.resetToDefault();
        if (oldTicks != this.ticks.getIntegerValue()) this.onValueChanged();
    }

    @Override
    public JsonElement getAsJsonElement() {
        return new JsonPrimitive(this.ticks.getIntegerValue());
    }

    @Override
    public void setValueFromJsonElement(JsonElement element) {
        this.ticks.setValueFromJsonElement(element);
    }

}
