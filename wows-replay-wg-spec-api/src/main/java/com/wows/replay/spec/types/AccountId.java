package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Player account identifier (u32).
 */
public record AccountId(int value) implements Comparable<AccountId> {

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(AccountId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
