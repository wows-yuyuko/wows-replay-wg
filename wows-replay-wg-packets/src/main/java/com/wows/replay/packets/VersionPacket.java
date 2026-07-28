package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x16: Version string.
 */
public record VersionPacket(
    @JsonProperty("version") String version
) {}
