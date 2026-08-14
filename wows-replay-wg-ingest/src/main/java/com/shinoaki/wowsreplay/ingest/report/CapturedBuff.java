package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 已捕获的 Buff（对标 Rust {@code CapturedBuff}，report.rs §3）。
 * 由队伍捕获（{@code team_id}）；{@code params_id} 为 Drop（powerup）类型，无实体 id。
 */
public record CapturedBuff(
    @JsonProperty("params_id") long paramsId,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("clock") float clock
) {}
