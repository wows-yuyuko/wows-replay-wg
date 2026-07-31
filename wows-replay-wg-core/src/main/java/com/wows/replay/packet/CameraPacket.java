package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.Vec3;

/**
 * 0x25(新版)/ 0x24(旧版): 相机状态(60 字节)。每 tick 与 GunMarker 和 PlayerNetStats 一起写入。 */
public record CameraPacket(
    @JsonProperty("rotation_quat") float[] rotationQuat,
    @JsonProperty("camera_position") Vec3 cameraPosition,
    @JsonProperty("fov") float fov,
    @JsonProperty("unknown") float unknown,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction
) {}
