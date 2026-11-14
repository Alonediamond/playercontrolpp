package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.util.PinyinTextSearch;

/** 配置名与英文键共用搜索规则的无窗口回归。 */
public final class ConfigSearchRegression {
    private ConfigSearchRegression() {}

    public static void main(String[] args) {
        System.out.println("config search regression: " + run() + " checks");
    }

    public static int run() {
        String label = "备货：矿物搜索半径";
        String key = "miningSearchRadius";
        check(PinyinTextSearch.matches("矿物", label, key), "中文包含");
        check(PinyinTextSearch.matches("kuangwu", label, key), "全拼包含");
        check(PinyinTextSearch.matches("bhkwssbj", label, key), "首字母包含");
        check(PinyinTextSearch.matches("SEARCHRADIUS", label, key), "英文键忽略大小写");
        check(PinyinTextSearch.matches("none|kuangwu", label, key), "malilib 或条件");
        check(!PinyinTextSearch.matches("stone", label, key), "不相关词不命中");
        return 6;
    }

    private static void check(boolean result, String caseName) {
        if (!result) throw new AssertionError(caseName);
    }
}
