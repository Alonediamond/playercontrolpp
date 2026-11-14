package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 物品名、注册名、中文全拼和拼音首字母的包含搜索。 */
public final class ManualMaterialSearch {
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();
    private static final Map<String, String[]> CACHE = new HashMap<>();

    static {
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private ManualMaterialSearch() {}

    public static boolean matches(String query, String name, String itemId) {
        if (query == null || query.isBlank()) return true;
        String needle = normalize(query);
        if (needle.isEmpty()) return true;
        String label = name == null ? "" : name;
        String id = itemId == null ? "" : itemId;
        if (label.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))
                || id.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))) return true;
        String[] pair = CACHE.computeIfAbsent(label, ManualMaterialSearch::toPinyin);
        return pair[0].contains(needle) || pair[1].contains(needle);
    }

    private static String[] toPinyin(String name) {
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                try {
                    String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
                    if (readings != null && readings.length > 0 && !readings[0].isEmpty()) {
                        full.append(readings[0]);
                        initials.append(readings[0].charAt(0));
                    }
                } catch (BadHanyuPinyinOutputFormatCombination ignored) {
                    // 单字无法转换时，仍可按原名及注册名搜索。
                }
            } else if (Character.isLetterOrDigit(c)) {
                String part = String.valueOf(c).toLowerCase(Locale.ROOT);
                full.append(part);
                initials.append(part);
            }
        }
        return new String[] { full.toString(), initials.toString() };
    }

    private static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) result.append(c);
        }
        return result.toString();
    }
}
