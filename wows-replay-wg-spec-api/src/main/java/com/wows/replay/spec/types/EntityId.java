package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * BigWorld entity identifier (u32).
 */
public record EntityId(int value) implements Comparable<EntityId> {

    public static final EntityId ZERO = new EntityId(0);

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(EntityId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
