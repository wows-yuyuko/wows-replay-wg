package com.shinoaki.wowsreplay.core.data;

import com.shinoaki.wowsreplay.core.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 多语言字符串表（{@code app/lang/lang.json} 的 en/ja/zh_sg 子集）。
 *
 * <p>lang.json 顶层为语言键（{@code "en"}/{@code "ja"}/{@code "zh_sg"}/…），
 * 每语言是一个 {@code IDS_XXX → 文本} 扁平表。本类只保留 en/ja/zh_sg，
 * 查 key 未命中（语言不存在或 key 不存在）时返回 key 本身。</p>
 */
public final class LangProvider {

    /** 默认语言（未指定语言时）。 */
    public static final Lang DEFAULT_LANG = Lang.ZH_SG;
    /** 空实现（lang.json 缺失/解析失败时）。 */
    public static final LangProvider EMPTY = new LangProvider(Map.of());

    private final Map<Lang, Map<String, String>> tables; // lang -> (key -> value)

    private LangProvider(Map<Lang, Map<String, String>> tables) {
        this.tables = tables;
    }

    public enum Lang {
        EN, JA, ZH_SG
    }

    /** 默认语言（zh_sg）查 key，未命中返回 key 本身。 */
    public String get(String key) {
        return get(DEFAULT_LANG, key);
    }

    /** 指定语言查 key，未命中返回 key 本身。 */
    public String get(Lang lang, String key) {
        Map<String, String> table = tables.get(lang);
        if (table == null) return key;
        return table.getOrDefault(key, key);
    }

    /** 从 lang.json 文本解析（只保留 en/ja/zh_sg）；解析异常返回 {@link #EMPTY}。 */
    public static LangProvider fromJson(String json) {
        try {
            JsonNode root = JsonMapper.readTree(json);
            Map<Lang, Map<String, String>> tables = new HashMap<>();
            for (var lang : Lang.values()) {
                JsonNode node = root.get(lang.name().toLowerCase(Locale.ROOT));
                if (node == null || !node.isObject()) continue;
                Map<String, String> table = new HashMap<>();
                for (var p : node.properties()) {
                    table.put(p.getKey(), p.getValue().asString());
                }
                tables.put(lang, table);
            }
            return tables.isEmpty() ? EMPTY : new LangProvider(tables);
        } catch (Exception e) {
            return EMPTY;
        }
    }
}
