package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Weapon lock state enumerating the four lock modes.
 * Mirrors Rust's {@code wowsunpack::game_types::WeaponLockType}.
 */
public enum WeaponLockType {
    /** No lock (an unlock). */
    NONE,
    /** Lock onto a fixed point in world space. */
    ABSOLUTE,
    /** Lock onto a point relative to the firing ship. */
    RELATIVE,
    /** Hard lock onto a target entity. */
    TARGET;

    private static final Map<Integer, WeaponLockType> REVERSE = Stream.of(values())
        .collect(Collectors.toUnmodifiableMap(
            w -> switch (w) {
                case NONE     -> 0;
                case ABSOLUTE -> 1;
                case RELATIVE -> 2;
                case TARGET   -> 3;
            },
            w -> w));

    @JsonValue
    public String wireName() {
        return name();
    }

    /**
     * Map a raw wire value to a {@link Recognized} variant.
     */
    public static Recognized<WeaponLockType> fromRaw(int raw) {
        var known = REVERSE.get(raw);
        return known != null ? new Recognized.Known<>(known) : new Recognized.Unknown<>(raw);
    }
}
