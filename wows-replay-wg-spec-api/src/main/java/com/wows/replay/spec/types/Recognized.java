package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.Optional;

/**
 * Recognized enum value — either a known variant or an unknown raw value.
 * Mirrors Rust's {@code Recognized<T, u32>}.
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
     * Create from raw value using a reverse lookup map.
     */
    static <T extends Enum<T>> Recognized<T> fromRaw(int raw, Map<Integer, T> reverseMap) {
        T known = reverseMap.get(raw);
        return known != null ? new Known<>(known) : new Unknown<>(raw);
    }

    /**
     * Convenience factory for an always-unknown value (no reverse map available).
     */
    static Recognized<?> unknown(int raw) {
        return new Unknown<>(raw);
    }

    /**
     * Return the known value, if recognized.
     */
    default Optional<T> intoKnown() {
        return this instanceof Known<T> k ? Optional.of(k.value) : Optional.empty();
    }
}
