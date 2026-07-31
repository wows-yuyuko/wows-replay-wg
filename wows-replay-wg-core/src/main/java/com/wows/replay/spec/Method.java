package com.wows.replay.spec;

import com.wows.replay.types.ArgType;

import java.util.List;

/**
 * RPC method definition.
 */
public record Method(
    /** Method name (e.g. "onShoot", "onDamageReceived") */
    String name,

    /** Method argument types (in wire order) */
    List<ArgSpec> args,

    /** Method index within the entity's method array */
    int index
) {}
