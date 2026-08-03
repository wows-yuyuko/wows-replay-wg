package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;

/**
 * 建筑实体（对标 Rust {@code BuildingEntity}，report.rs §5.6）。
 */
public record BuildingEntity(
    @JsonProperty("id") EntityId id,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z,
    @JsonProperty("is_alive") boolean isAlive,
    @JsonProperty("is_hidden") boolean isHidden,
    @JsonProperty("is_suppressed") boolean isSuppressed,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("params_id") GameParamId paramsId
) {}
