package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x27: Camera mode.
 */
public record CameraModePacket(
    @JsonProperty("mode") int mode
) {}
