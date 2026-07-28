package com.wows.replay.core;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Shared Jackson {@link ObjectMapper} holder.
 *
 * <p>{@link ObjectMapper} is thread-safe once configured, so a single static
 * instance is shared across the application. Use {@link #mapper()} for the
 * default (compact) instance and {@link #pretty()} for pretty-printed output.</p>
 */
public final class JsonMapper {

    private static final ObjectMapper DEFAULT = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper();

    static {
        PRETTY.isEnabled(SerializationFeature.INDENT_OUTPUT);
    }

    private JsonMapper() { /* utility class */ }

    /** Default compact ObjectMapper (shared, thread-safe). */
    public static ObjectMapper mapper() {
        return DEFAULT;
    }

    /** Pretty-printing ObjectMapper (shared, thread-safe). */
    public static ObjectMapper pretty() {
        return PRETTY;
    }
}
