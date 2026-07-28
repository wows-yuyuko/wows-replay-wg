package com.wows.replay.core;

import com.wows.replay.core.json.JacksonProvider;
import com.wows.replay.core.json.JNode;
import com.wows.replay.core.json.JObject;
import com.wows.replay.core.json.JsonProvider;

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

    public static JNode readTree(byte[] bytes)          { return provider.readTree(bytes); }
    public static JNode readTree(String json)           { return provider.readTree(json); }
    public static <T> T fromJson(String json, Class<T> type) { return provider.fromJson(json, type); }
    public static String toJson(Object obj)             { return provider.toJson(obj); }
    public static String toPrettyJson(Object obj)       { return provider.toPrettyJson(obj); }
    public static String toJson(JObject obj)            { return provider.toJson(obj); }
    public static JNode toTree(Object obj)              { return provider.toTree(obj); }
    public static JObject createObject()                { return provider.createObject(); }
}
