package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x1d: Player network stats (every tick).
 * Packs fps, ping, isLagging into a single u32.
 */
public record PlayerNetStatsPacket(
    @JsonProperty("fps") int fps,
    @JsonProperty("ping") int ping,
    @JsonProperty("is_lagging") boolean isLagging
) {}
