package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.Vec3;

/**
 * Packet 0x25 (modern) / 0x24 (legacy): Camera state (60 bytes).
 * Written every tick alongside GunMarker and PlayerNetStats.
 */
public record CameraPacket(
    @JsonProperty("rotation_quat") float[] rotationQuat,
    @JsonProperty("camera_position") Vec3 cameraPosition,
    @JsonProperty("fov") float fov,
    @JsonProperty("unknown") float unknown,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction
) {}
