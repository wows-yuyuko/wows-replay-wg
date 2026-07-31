package com.wows.replay.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.Optional;

/**
 * 可识别的枚举值 — either a known variant or an unknown raw value.
 * 对标 Rust {@code Recognized<T, u32>}.
 */
public sealed interface Recognized<T extends Enum<T>> {

    record Known<T extends Enum<T>>(T value) implements Recognized<T> {
        @Override
        @JsonValue
        public T value() { return value; }
    }

    record Unknown<T extends Enum<T>>(int raw) implements Recognized<T> {
        @Override
        @JsonValue
        public int raw() { return raw; }
    }

    /**
     * 用反向查找 map 从原始值创建。
     */
    static <T extends Enum<T>> Recognized<T> fromRaw(int raw, Map<Integer, T> reverseMap) {
        T known = reverseMap.get(raw);
        return known != null ? new Known<>(known) : new Unknown<>(raw);
    }

    /**
     * 始终为未知值的便捷工厂方法。
     */
    static Recognized<?> unknown(int raw) {
        return new Unknown<>(raw);
    }

    /**
     * 返回已知值（如果可识别）。
     */
    default Optional<T> intoKnown() {
        return this instanceof Known<T> k ? Optional.of(k.value) : Optional.empty();
    }
}
