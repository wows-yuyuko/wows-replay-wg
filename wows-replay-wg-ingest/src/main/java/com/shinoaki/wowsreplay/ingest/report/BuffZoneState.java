package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 军备竞赛 Buff 掉落区（BuffZoneState，）。
 */
public record BuffZoneState(
    @JsonProperty("entity_id") int entityId,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z,
    @JsonProperty("radius") float radius,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("is_active") boolean isActive,
    @JsonProperty("clock") float clock
) {}
