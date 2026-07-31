package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x32: 巡航状态变更。
 */
public record CruiseStatePacket(
    @JsonProperty("key") int key,
    @JsonProperty("value") int value
) {}
