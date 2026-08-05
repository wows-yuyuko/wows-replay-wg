package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.Vec3;

/**
 * 战舰属性（对标 Rust {@code VehicleProps}，report.rs §2.3）。
 * 实体最后一次已知的战舰状态快照。
 */
public record VehicleProps(
    @JsonProperty("health") float health,
    @JsonProperty("max_health") float maxHealth,
    @JsonProperty("is_alive") boolean isAlive,
    @JsonProperty("is_invisible") boolean isInvisible,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("heading") float heading
) {}
