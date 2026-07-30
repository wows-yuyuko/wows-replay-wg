package com.wows.replay.core.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 玩家账号标识（u32）。
 */
public record AccountId(int value) implements Comparable<AccountId> {

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(AccountId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
