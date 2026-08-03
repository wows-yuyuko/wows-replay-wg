package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 队伍比分（对标 Rust {@code TeamScore}，report.rs §3）。
 */
public record TeamScore(
    @JsonProperty("team_index") int teamIndex,
    @JsonProperty("score") long score
) {}
