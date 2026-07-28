package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x31: Submarine controller mode change.
 */
public record SubControllerPacket(
    @JsonProperty("mode") short mode
) {}
