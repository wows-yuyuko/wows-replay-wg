package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 竞技场标识（i64）。
 */
public record ArenaId(long value) implements Comparable<ArenaId> {

    @JsonValue
    public long value() { return value; }

    @Override
    public int compareTo(ArenaId o) { return Long.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
