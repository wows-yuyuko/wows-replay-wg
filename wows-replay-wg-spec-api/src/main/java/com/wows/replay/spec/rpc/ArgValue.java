package com.wows.replay.spec.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Map;

/**
 * A decoded RPC argument value.
 * Sealed type hierarchy covering all BigWorld wire types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface ArgValue {

    record IntVal(long value) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return value; }
    }
    record FloatVal(double value) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return value; }
    }
    record StrVal(java.lang.String value) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return value; }
    }
    record BoolVal(boolean value) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return value; }
    }
    record BlobVal(byte[] value) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return "[blob " + value.length + " bytes]"; }
    }
    record ArrayVal(List<ArgValue> elements) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return elements; }
    }
    record TupleVal(List<ArgValue> elements) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return elements; }
    }
    record DictVal(Map<java.lang.String, ArgValue> entries) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return entries; }
    }
    /** Vector2: [x, y] */
    record Vec2Val(float x, float y) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return List.of(x, y); }
    }
    /** Vector3: [x, y, z] */
    record Vec3Val(float x, float y, float z) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return List.of(x, y, z); }
    }
    /** Vector4: [x, y, z, w] */
    record Vec4Val(float x, float y, float z, float w) implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return List.of(x, y, z, w); }
    }
    /** Null / unset value */
    record NullVal() implements ArgValue {
        @Override @JsonValue public Object jsonValue() { return null; }
    }

    /**
     * Return a JSON-compatible representation (used by Jackson).
     */
    @JsonValue
    Object jsonValue();
}
