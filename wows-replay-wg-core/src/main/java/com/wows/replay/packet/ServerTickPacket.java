package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x0e: 服务器 tick 速率（恒为 1/7）。
 */
public record ServerTickPacket(
    @JsonProperty("tick_rate") double tickRate
) {}
