package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x10: Init flag (always 0 at clock=0).
 */
public record InitFlagPacket(
    @JsonProperty("flag") int flag
) {}
