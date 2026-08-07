package com.wows.replay.spec;

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
    int index,

    /** BigWorld variable-length header size (defaults to 1). Affects method sort order. */
    int variableLengthHeaderSize
) {
    /** 便捷构造器：未指定 VariableLengthHeaderSize 时默认 1（与 Rust 一致）。 */
    public Method(String name, List<ArgSpec> args, int index) {
        this(name, args, index, 1);
    }
}
