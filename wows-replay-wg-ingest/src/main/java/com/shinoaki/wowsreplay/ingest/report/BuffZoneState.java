package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 军备竞赛 Buff 掉落区（对标 Rust {@code BuffZoneState}，report.rs §3）。
 */
public record BuffZoneState(
    @JsonProperty("entity_id") int entityId,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z,
    @JsonProperty("radius") float radius,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("is_active") boolean isActive,
    @JsonProperty("drop_params_id") Long dropParamsId,
    @JsonProperty("clock") float clock
) {}
