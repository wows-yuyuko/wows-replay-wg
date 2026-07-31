package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x2f: 相机自由视角切换。
 */
public record CameraFreeLookPacket(
    @JsonProperty("free_look") int freeLook
) {}
