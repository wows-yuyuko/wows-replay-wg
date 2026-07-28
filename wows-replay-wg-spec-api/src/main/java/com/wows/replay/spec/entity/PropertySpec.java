package com.wows.replay.spec.entity;

import com.wows.replay.spec.rpc.ArgType;

/**
 * Entity property definition.
 */
public record PropertySpec(
    /** Property name (e.g. "position", "health", "maxHealth") */
    String name,

    /** Wire type of this property */
    ArgType propType,

    /** Index within the entity's property array */
    int index
) {}
