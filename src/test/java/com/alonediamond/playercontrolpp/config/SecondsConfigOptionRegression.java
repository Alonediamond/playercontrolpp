package com.alonediamond.playercontrolpp.config;

import com.google.gson.JsonPrimitive;

/** 无窗口验证秒数与旧整数 tick JSON 的转换；不初始化 malilib 配置类。 */
public final class SecondsConfigOptionRegression {
    private SecondsConfigOptionRegression() {}

    public static int run() {
        int checks = 0;
        int storedTicks = TickDuration.clampTicks(new JsonPrimitive(1200).getAsInt(), 100, 12000);
        check(TickDuration.formatSeconds(storedTicks).equals("60"), "旧 JSON 的 1200 tick 显示为 60 秒"); checks++;
        check(new JsonPrimitive(storedTicks).getAsInt() == 1200, "旧 JSON 数值仍按 tick 写回"); checks++;
        check(TickDuration.parseSeconds("60", 100, 12000) == 1200, "显示值写回还原旧 tick 值"); checks++;
        check(TickDuration.parseSeconds("0.5", 100, 12000) == 100, "秒数输入受原 tick 下限约束"); checks++;
        check(TickDuration.parseSeconds("9999", 100, 12000) == 12000, "秒数输入受原 tick 上限约束"); checks++;
        check(TickDuration.parseSeconds("5.05", 100, 12000) == 101
                && TickDuration.formatSeconds(101).equals("5.05"), "小数秒保留单 tick 精度"); checks++;
        check(TickDuration.parseSeconds("60.025", 100, 12000) == 1201, "半 tick 向上舍入"); checks++;
        check(TickDuration.toTicks(5.05, 100, 12000) == 101, "GUI 数值输入与文本输入一致"); checks++;
        check(TickDuration.parseSeconds("0", 0, 600) == 0, "零秒可关闭高亮"); checks++;
        check(TickDuration.clampTicks(30000, 100, 12000) == 12000, "旧 JSON 数值遵守原 tick 边界"); checks++;
        try {
            TickDuration.parseSeconds("invalid", 100, 12000);
            throw new AssertionError("无效秒数必须被拒绝");
        } catch (NumberFormatException expected) {
            checks++;
        }
        return checks;
    }

    private static void check(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
    }
}
