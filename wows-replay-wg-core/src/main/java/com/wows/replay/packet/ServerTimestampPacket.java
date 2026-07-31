package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x0f: 服务器时间戳（f64, clock=0 时）。
 */
public record ServerTimestampPacket(
    @JsonProperty("timestamp") double timestamp
) {}
