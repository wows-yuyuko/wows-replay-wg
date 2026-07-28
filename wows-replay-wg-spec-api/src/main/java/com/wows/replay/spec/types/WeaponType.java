package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Weapon type enumerating selectable armaments.
 * Mirrors Rust's {@code wowsunpack::game_types::WeaponType}.
 *
 * <p>Raw wire values come from the client's integer {@code WeaponType}
 * enum ({@code scripts/WeaponType.pyc}), which is wider than the
 * selectable weapons modeled here.  Non-selectable types are preserved
 * as {@link Recognized.Unknown}.</p>
 */
public enum WeaponType {
    ARTILLERY,
    /** Secondary battery. */
    SECONDARIES,
    TORPEDOES,
    PLANES,
    /** Sonar pinger (submarines). */
    PINGER;

    private static final Map<Integer, WeaponType> REVERSE = Stream.of(values())
        .collect(Collectors.toUnmodifiableMap(
            w -> switch (w) {
                case ARTILLERY  -> 0;
                case SECONDARIES -> 1;
                case TORPEDOES  -> 2;
                case PLANES     -> 3;
                case PINGER     -> 6;
            },
            w -> w));

    /** Wire-level name used in Python entity defs. */
    @JsonValue
    public String wireName() {
        return switch (this) {
            case ARTILLERY  -> "ARTILLERY";
            case SECONDARIES -> "ATBA";
            case TORPEDOES  -> "TORPEDO";
            case PLANES     -> "AIRPLANES";
            case PINGER     -> "PINGER";
        };
    }

    /** Human-readable description. */
    public String description() {
        return switch (this) {
            case ARTILLERY  -> "Main Battery";
            case SECONDARIES -> "Secondaries";
            case TORPEDOES  -> "Torpedoes";
            case PLANES     -> "Planes";
            case PINGER     -> "Sonar";
        };
    }

    /**
     * Map a raw wire value to a {@link Recognized} variant.
     * Returns {@link Recognized.Unknown} for non-selectable weapon types.
     */
    public static Recognized<WeaponType> fromRaw(int raw) {
        var known = REVERSE.get(raw);
        return known != null ? new Recognized.Known<>(known) : new Recognized.Unknown<>(raw);
    }
}
