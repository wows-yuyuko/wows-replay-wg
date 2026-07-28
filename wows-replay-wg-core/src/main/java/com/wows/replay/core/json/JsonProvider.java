package com.wows.replay.core.json;

/**
 * Abstraction over JSON serialization/deserialization.
 *
 * <p>All JSON operations go through this interface so the underlying
 * library (Jackson, Gson, etc.) can be swapped without touching
 * business logic.  Obtain the default instance via {@link com.wows.replay.core.JsonMapper}.</p>
 *
 * <p>Jackson 3 throws {@code JacksonException} (unchecked), so this interface
 * declares no checked exceptions.</p>
 */
public interface JsonProvider {

    /** Parse JSON bytes into a {@link JNode} tree. */
    JNode readTree(byte[] bytes);

    /** Parse JSON string into a {@link JNode} tree. */
    JNode readTree(String json);

    /** Deserialize JSON string into a Java object. */
    <T> T fromJson(String json, Class<T> type);

    /** Serialize an object to compact JSON. */
    String toJson(Object obj);

    /** Serialize an object to pretty-printed JSON. */
    String toPrettyJson(Object obj);

    /** Serialize a {@link JNode} tree to compact JSON. */
    String nodeToJson(JNode node);

    /** Serialize a {@link JObject} builder to compact JSON. */
    String toJson(JObject obj);

    /** Convert a POJO directly to a {@link JNode} tree. */
    JNode toTree(Object obj);

    /** Create an empty JSON object builder. */
    JObject createObject();
}
