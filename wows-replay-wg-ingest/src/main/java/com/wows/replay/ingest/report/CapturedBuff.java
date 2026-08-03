package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 已捕获的 Buff（对标 Rust {@code CapturedBuff}，report.rs §3）。
 */
public record CapturedBuff(
    @JsonProperty("entity_id") int entityId,
    @JsonProperty("params_id") long paramsId,
    @JsonProperty("captured_by") int capturedBy,
    @JsonProperty("clock") float clock
) {}
