package com.wows.replay.spec.entity;

import com.wows.replay.spec.rpc.ArgType;

/**
 * Single method argument definition.
 */
public record ArgSpec(
    /** Argument name */
    String name,

    /** Wire type of this argument */
    ArgType argType,

    /** Position in the method's argument list */
    int index
) {}
