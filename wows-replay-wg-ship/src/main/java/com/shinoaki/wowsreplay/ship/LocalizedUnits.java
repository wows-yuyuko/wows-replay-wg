package com.shinoaki.wowsreplay.ship;

import com.shinoaki.wowsreplay.core.data.LangProvider;

/**
 * 显示用单位后缀（读取 lang.json 的 {@code IDS_KNOT} 等键本地化）。
 *
 * <p>对齐 libwowsinfo {@code wiki/lang.rs} 的 {@code LocalizedUnits}：
 * 拉丁字母单位带前导空格（{@code " knots"}/{@code " s"}），CJK 单位不带
 * （{@code "节"}/{@code "秒"}），便于模板直接拼接数值。</p>
 */
public record LocalizedUnits(
        String knots,
        String seconds,
        String kilometer,
        String meter,
        String meterPerSecond,
        String millimeter,
        String kilogram
) {

    public static LocalizedUnits fromLang(LangProvider lang) {
        return fromLang(lang, LangProvider.DEFAULT_LANG);
    }

    public static LocalizedUnits fromLang(LangProvider lang, LangProvider.Lang langCode) {
        return new LocalizedUnits(
                suffix(lang, langCode, "IDS_KNOT", "knots"),
                suffix(lang, langCode, "IDS_SECOND", "s"),
                suffix(lang, langCode, "IDS_KILOMETER", "km"),
                suffix(lang, langCode, "IDS_METER", "m"),
                suffix(lang, langCode, "IDS_METER_SECOND", "m/s"),
                suffix(lang, langCode, "IDS_MILLIMETER", "mm"),
                suffix(lang, langCode, "IDS_KILOGRAMM", "kg"));
    }

    private static String suffix(LangProvider lang, LangProvider.Lang langCode, String key, String fallback) {
        String value = lang.get(langCode, key);
        if (value == null || value.isEmpty() || value.equals(key)) {
            value = fallback;
        }
        return hasCjk(value) ? value : " " + value;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }
}
