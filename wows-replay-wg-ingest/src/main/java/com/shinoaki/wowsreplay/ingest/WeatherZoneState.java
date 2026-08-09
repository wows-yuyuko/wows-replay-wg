package com.shinoaki.wowsreplay.ingest;

/** 天气区域状态。 */
public record WeatherZoneState(String name, float x, float z, float radius,
                               long paramsId, Integer entityId) {
}
