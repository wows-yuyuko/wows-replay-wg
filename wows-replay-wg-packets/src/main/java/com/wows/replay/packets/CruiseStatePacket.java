package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x32: Cruise state change.
 */
public record CruiseStatePacket(
    @JsonProperty("key") int key,
    @JsonProperty("value") int value
) {}
