package com.wows.replay.spec.entity;

import com.wows.replay.spec.rpc.ArgType;

/**
 * Entity property definition.  对标 Rust's {@code entitydefs::Property}.
 */
public record PropertySpec(
    /** Property name (e.g. "position", "health", "maxHealth"). */
    String name,

    /** Wire type of this property. */
    ArgType propType,

    /** Visibility flag controlling when this property is transmitted. */
    PropertyFlags flags,

    /** Index within the entity's sorted property array. */
    int index
) {
    /** Convenience constructor for creating specs without flags (defaults to ALL_CLIENTS). */
    public PropertySpec(String name, ArgType propType, int index) {
        this(name, propType, PropertyFlags.ALL_CLIENTS, index);
    }
}
