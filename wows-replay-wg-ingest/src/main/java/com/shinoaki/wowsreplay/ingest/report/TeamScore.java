package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 队伍比分（TeamScore，）。
 */
public record TeamScore(
    @JsonProperty("team_index") int teamIndex,
    @JsonProperty("score") long score
) {}
