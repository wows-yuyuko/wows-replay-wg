package com.wows.replay.core.json;

/**
 * Mutable JSON array builder.
 */
public sealed interface JArray permits JacksonProvider.JacksonArrayBuilder {

    JArray add(String value);
    JArray add(int value);
    JArray add(long value);
    JArray add(JNode node);
}
