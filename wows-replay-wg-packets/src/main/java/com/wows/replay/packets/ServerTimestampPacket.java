package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x0f: Server timestamp (f64 at clock=0).
 */
public record ServerTimestampPacket(
    @JsonProperty("timestamp") double timestamp
) {}
