package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 局部天气区域（LocalWeatherZone， WeatherZoneOrder）。
 */
public record LocalWeatherZone(
    @JsonProperty("name") String name,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z,
    @JsonProperty("radius") float radius,
    @JsonProperty("params_id") long paramsId,
    @JsonProperty("entity_id") Integer entityId
) {}
