package com.wows.replay.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/**

 */
public final class JsonMapper {


    private JsonMapper() {
    }

    /** Get the current provider. */
    private static final ObjectMapper provider = new ObjectMapper();

    static {
        provider.isEnabled(SerializationFeature.INDENT_OUTPUT);
    }

    public static ObjectMapper getMapper() {
        return provider;
    }

    // ── Convenience shortcuts ────────────────────────────────────────────────

    public static JsonNode readTree(byte[] bytes) {
        return provider.readValue(bytes, JsonNode.class);
    }

    public static JsonNode readTree(String json) {
        return provider.readTree(json);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return provider.readValue(json, type);
    }

    public static String toJson(Object obj) {
        return provider.writeValueAsString(obj);
    }

    public static String toPrettyJson(Object obj) {
        return provider.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }


    public static JsonNode toTree(Object obj) {
        return provider.readValue(toJson(obj), JsonNode.class);
    }

    public static ObjectNode createObject() {
        return provider.createObjectNode();
    }
}
