package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 队伍标识（i32），队伍 1 vs 队伍 2。
 */
public record TeamId(int value) implements Comparable<TeamId> {

    public static final TeamId TEAM_1 = new TeamId(1);
    public static final TeamId TEAM_2 = new TeamId(2);

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(TeamId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
