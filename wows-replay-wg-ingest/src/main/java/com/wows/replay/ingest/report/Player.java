package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 玩家（对标 Rust {@code Player}，report.rs §3）。
 */
public record Player(
    @JsonProperty("db_id") long dbId,
    @JsonProperty("username") String username,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("relation") int relation,
    @JsonProperty("is_bot") boolean isBot,
    @JsonProperty("vehicle_entity") VehicleEntity vehicleEntity
) {}
