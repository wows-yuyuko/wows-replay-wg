package com.wows.replay.core.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 游戏参数标识（u32） — indexes into GameParams.data.
 * 用 {@code long} 保存完整的无符号 32 位范围。
 */
public record GameParamId(long value) implements Comparable<GameParamId> {

    public static final GameParamId ZERO = new GameParamId(0);

    @JsonCreator
    public GameParamId {}

    /** 便捷构造：u32 线值转无符号 long。 */
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
