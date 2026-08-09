package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.types.ArgType;

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
