package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Game parameter identifier (u32) — indexes into GameParams.data.
 * Uses {@code long} to hold the full unsigned 32-bit range.
 */
public record GameParamId(long value) implements Comparable<GameParamId> {

    public static final GameParamId ZERO = new GameParamId(0);

    @JsonCreator
    public GameParamId {}

    /** Convenience: convert wire u32 (read as signed int) to unsigned long. */
    public GameParamId(int value) {
        this(Integer.toUnsignedLong(value));
    }

    @JsonValue
    public long value() { return value; }

    @Override
    public int compareTo(GameParamId o) { return Long.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
