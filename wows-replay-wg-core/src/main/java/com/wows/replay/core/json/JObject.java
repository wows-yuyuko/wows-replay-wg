package com.wows.replay.core.json;

/**
 * Mutable JSON object builder.
 *
 * <p>Fluent API for constructing JSON objects without coupling to a
 * specific JSON library.</p>
 */
public sealed interface JObject permits JacksonProvider.JacksonBuilder {

    JObject put(String name, String value);
    JObject put(String name, int value);
    JObject put(String name, long value);
    JObject put(String name, boolean value);

    JObject set(String name, JNode node);
}
