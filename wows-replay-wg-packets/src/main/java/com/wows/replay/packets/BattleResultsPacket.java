package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x22 (modern only): Battle results JSON (appears near end of replay).
 */
public record BattleResultsPacket(
    @JsonProperty("json") String json
) {}
