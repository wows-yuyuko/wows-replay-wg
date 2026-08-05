package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 死亡信息（对标 Rust {@code DeathInfo}，report.rs §5.2）。
 */
public record DeathInfo(
    @JsonProperty("time_lived") float timeLived,
    @JsonProperty("killer") EntityId killer,
    @JsonProperty("cause") int cause
) {
    /**
     * 从击杀记录构造，注意 30 秒偏移（TIME_UNTIL_GAME_START=30s）。
     */
    public static DeathInfo from(com.wows.replay.ingest.KillRecord kill) {
        return new DeathInfo(
            Math.max(0f, kill.clock() - 30.0f),
            new EntityId(kill.killerEid()),
            kill.cause());
    }
}
