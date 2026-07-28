package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x0e: Server tick rate (always 1/7).
 */
public record ServerTickPacket(
    @JsonProperty("tick_rate") double tickRate
) {}
