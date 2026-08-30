package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.GameParamId;

/**
 * 建筑实体（BuildingEntity，）。
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
