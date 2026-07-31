package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.Vec3;

/**
 * 0x25锛堟柊鐗堬級/ 0x24锛堟棫鐗堬級: 鐩告満鐘舵€侊紙60 瀛楄妭锛夈€? * 姣?tick 涓?GunMarker 鍜?PlayerNetStats 涓€璧峰啓鍏ャ€? */
public record CameraPacket(
    @JsonProperty("rotation_quat") float[] rotationQuat,
    @JsonProperty("camera_position") Vec3 cameraPosition,
    @JsonProperty("fov") float fov,
    @JsonProperty("unknown") float unknown,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction
) {}
