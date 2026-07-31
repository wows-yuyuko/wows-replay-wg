package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x27: 相机模式。
 */
public record CameraModePacket(
    @JsonProperty("mode") int mode
) {}
