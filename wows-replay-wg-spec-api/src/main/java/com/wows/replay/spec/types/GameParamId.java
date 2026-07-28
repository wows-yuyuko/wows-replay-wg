package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Game parameter identifier (u32) — indexes into GameParams.data.
 */
public record GameParamId(int value) implements Comparable<GameParamId> {

    public static final GameParamId ZERO = new GameParamId(0);

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(GameParamId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
