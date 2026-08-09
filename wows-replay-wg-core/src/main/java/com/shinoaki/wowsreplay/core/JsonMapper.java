package com.shinoaki.wowsreplay.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 全局 JSON 工具类，基于 Jackson 3 的 {@link ObjectMapper}。
 *
 * 解析不需要处理异常
 */
public final class JsonMapper {

    private JsonMapper() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 获取底层 {@link ObjectMapper} 实例（流式解析等高级场景）。 */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    // ── 快捷方法 ────────────────────────────────────────────────────────────

    /** 从字节数组解析 JSON 树。 */
    public static JsonNode readTree(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }

    /** 从字符串解析 JSON 树。 */
    public static JsonNode readTree(String json) {
        return MAPPER.readTree(json);
    }

    /** JSON 字符串 → Java 对象。 */
    public static <T> T fromJson(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Java 对象 → 紧凑 JSON 字符串。 */
    public static String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Java 对象 → 美化 JSON 字符串。 */
    public static String toPrettyJson(Object obj) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** POJO → {@link JsonNode} 树节点（无需字符串中转）。 */
    public static JsonNode toTree(Object obj) {
        return MAPPER.valueToTree(obj);
    }

    /** 创建空的 JSON 对象构建器。 */
    public static ObjectNode createObject() {
        return MAPPER.createObjectNode();
    }
}
