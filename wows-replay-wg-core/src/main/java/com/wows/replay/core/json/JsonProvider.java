package com.wows.replay.core.json;

import java.io.IOException;

/**
 * Abstraction over JSON serialization/deserialization.
 *
 * <p>All JSON operations go through this interface so the underlying
 * library (Jackson, Gson, etc.) can be swapped without touching
 * business logic.  Obtain the default instance via {@link JsonMapper}.</p>
 */
public interface JsonProvider {

    /** Parse JSON bytes into a {@link JNode} tree. */
    JNode readTree(byte[] bytes) throws IOException;

    /** Parse JSON string into a {@link JNode} tree. */
    JNode readTree(String json) throws IOException;

    /** Deserialize JSON string into a Java object. */
    <T> T fromJson(String json, Class<T> type) throws IOException;

    /** Serialize an object to compact JSON. */
    String toJson(Object obj) throws IOException;

    /** Serialize an object to pretty-printed JSON. */
    String toPrettyJson(Object obj) throws IOException;

    /** Serialize a {@link JNode} tree to compact JSON. */
    String nodeToJson(JNode node) throws IOException;

    /** Serialize a {@link JObject} builder to compact JSON. */
    String toJson(JObject obj) throws IOException;

    /** Create an empty JSON object builder. */
    JObject createObject();
}
