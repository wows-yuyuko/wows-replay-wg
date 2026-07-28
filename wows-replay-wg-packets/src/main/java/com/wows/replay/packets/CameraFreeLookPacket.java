package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Packet 0x2f: Camera free look toggle.
 */
public record CameraFreeLookPacket(
    @JsonProperty("free_look") int freeLook
) {}
