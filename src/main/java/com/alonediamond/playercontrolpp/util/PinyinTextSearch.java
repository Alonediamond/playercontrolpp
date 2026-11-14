package com.alonediamond.playercontrolpp.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 中文显示名、英文键、全拼和首字母的包含搜索。 */
public final class PinyinTextSearch {
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();
    private static final Map<String, String[]> CACHE = new HashMap<>();

    static {
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinTextSearch() {}

    public static boolean matches(String query, String displayName, String englishKey) {
        if (query == null || query.isBlank()) return true;
        if (normalize(query).isEmpty()) return true;
        String name = displayName == null ? "" : displayName;
        String key = englishKey == null ? "" : englishKey;
        for (String alternative : query.split("\\|")) {
            String term = alternative.strip().toLowerCase(Locale.ROOT);
            if (term.isEmpty()) continue;
            if (name.toLowerCase(Locale.ROOT).contains(term) || key.toLowerCase(Locale.ROOT).contains(term)) {
                return true;
            }
            String normalized = normalize(term);
            if (!normalized.isEmpty()) {
                String[] pinyin = CACHE.computeIfAbsent(name, PinyinTextSearch::toPinyin);
                if (pinyin[0].contains(normalized) || pinyin[1].contains(normalized)) return true;
            }
        }
        return false;
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
                    // 单字无法转换时，仍可按显示名及英文键搜索。
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
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) result.append(c);
        }
        return result.toString();
    }
}
