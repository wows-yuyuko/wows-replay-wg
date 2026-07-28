package com.wows.replay.core;

import com.wows.replay.core.json.JacksonProvider;
import com.wows.replay.core.json.JNode;
import com.wows.replay.core.json.JObject;
import com.wows.replay.core.json.JsonProvider;

import java.io.IOException;

/**
 * Static accessor for the global {@link JsonProvider}.
 *
 * <p>All JSON operations go through this class.  The underlying provider
 * can be swapped to a different JSON library (Gson, etc.) by implementing
 * {@link JsonProvider} and replacing the instance here.</p>
 *
 * <p>Default implementation: Jackson 3 ({@link JacksonProvider}).</p>
 */
public final class JsonMapper {

    private static JsonProvider provider = new JacksonProvider();

    private JsonMapper() {}

    /** Get the current provider. */
    public static JsonProvider provider() { return provider; }

    /** Replace the provider (call once at startup). */
    public static void setProvider(JsonProvider p) { provider = p; }

    // ── Convenience shortcuts ────────────────────────────────────────────────

    /** Parse JSON bytes into a {@link JNode} tree. */
    public static JNode readTree(byte[] bytes) throws IOException {
        return provider.readTree(bytes);
    }

    /** Parse JSON string into a {@link JNode} tree. */
    public static JNode readTree(String json) throws IOException {
        return provider.readTree(json);
    }

    /** Deserialize JSON string into a Java object. */
    public static <T> T fromJson(String json, Class<T> type) throws IOException {
        return provider.fromJson(json, type);
    }

    /** Serialize an object to compact JSON. */
    public static String toJson(Object obj) throws IOException {
        return provider.toJson(obj);
    }

    /** Serialize an object to pretty-printed JSON. */
    public static String toPrettyJson(Object obj) throws IOException {
        return provider.toPrettyJson(obj);
    }

    /** Serialize a {@link JObject} builder to compact JSON. */
    public static String toJson(JObject obj) throws IOException {
        return provider.toJson(obj);
    }

    /** Create an empty JSON object builder. */
    public static JObject createObject() {
        return provider.createObject();
    }
}
