package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import com.alonediamond.playercontrolpp.util.PinyinTextSearch;

/** 物品名、注册名、中文全拼和拼音首字母的包含搜索。 */
public final class ManualMaterialSearch {
    private ManualMaterialSearch() {}

    public static boolean matches(String query, String name, String itemId) {
        return PinyinTextSearch.matches(query, name, itemId);
    }
}
