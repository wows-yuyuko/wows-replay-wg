package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 游戏时钟（f32），在回放中单调递增。
 */
public record GameClock(float seconds) implements Comparable<GameClock> {

    public static final GameClock ZERO = new GameClock(0.0f);

    @JsonValue
    public float seconds() { return seconds; }

    @Override
    public int compareTo(GameClock o) { return Float.compare(seconds, o.seconds); }

    @Override
    public String toString() { return String.format("%.2f", seconds); }
}
