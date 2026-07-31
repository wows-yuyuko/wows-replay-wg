package com.wows.replay.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Avatar entity ID (u32), distinct from ship EntityId.
 */
public record AvatarId(int value) implements Comparable<AvatarId> {

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(AvatarId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
