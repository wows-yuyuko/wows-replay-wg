package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.decode.PlayerStateData;

/**
 * 玩家（对标 Rust {@code Player}，report.rs §3）。
 * {@code metaId}=战斗内 meta id；{@code dbId}=账号 ID（accountDBID）；{@code entityId}=Avatar 实体 id；
 * {@code initialState}=onArenaStateReceived 竞技场名册快照（null 表示名册未到达/旧格式）。
 */
public record Player(
    @JsonProperty("meta_id") long metaId,
    @JsonProperty("db_id") long dbId,
    @JsonProperty("entity_id") int entityId,
    @JsonProperty("username") String username,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("relation") int relation,
    @JsonProperty("is_bot") boolean isBot,
    @JsonProperty("initial_state") PlayerStateData initialState,
    @JsonProperty("vehicle_entity") VehicleEntity vehicleEntity
) {}
