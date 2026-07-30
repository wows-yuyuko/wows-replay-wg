package com.wows.replay.core.entity;

import com.wows.replay.core.rpc.ArgType;

import java.util.List;

/**
 * RPC method definition.
 */
public record MethodSpec(
    /** Method name (e.g. "onShoot", "onDamageReceived") */
    String name,

    /** Method argument types (in wire order) */
    List<ArgSpec> args,

    /** Method index within the entity's method array */
    int index
) {}
