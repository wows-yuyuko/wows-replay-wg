package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.data.ShipConfig;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 战舰实体（对标 Rust {@code VehicleEntity}，report.rs §5.3）。
 */
public record VehicleEntity(
    @JsonProperty("id") EntityId id,
    @JsonProperty("visibility_changed_at") float visibilityChangedAt,
    @JsonProperty("props") VehicleProps props,
    @JsonProperty("captain") GameParamId captain,
    @JsonProperty("damage") double damage,
    @JsonProperty("death_info") DeathInfo deathInfo,
    @JsonProperty("results_info") JsonNode resultsInfo,
    @JsonProperty("frags") List<DeathInfo> frags,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("ship_config") ShipConfig shipConfig
) {}
