package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.data.ShipConfig;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.GameParamId;

/**
 * 战舰实体（VehicleEntity，）。
 */
public record VehicleEntity(
    @JsonProperty("id") EntityId id,
    @JsonProperty("visibility_changed_at") float visibilityChangedAt,
    @JsonProperty("captain") GameParamId captain,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("ship_config") ShipConfig shipConfig
) {}
