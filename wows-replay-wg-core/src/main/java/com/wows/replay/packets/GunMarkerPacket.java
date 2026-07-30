package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.Vec3;

/**
 * 0x18: 鐐爣/鐬勫噯鐘舵€侊紙姣?tick, 52 瀛楄妭锛夈€? */
public record GunMarkerPacket(
    @JsonProperty("target_point") Vec3 targetPoint,
    @JsonProperty("diameter") float diameter,
    @JsonProperty("marker_position") Vec3 markerPosition,
    @JsonProperty("marker_direction") Vec3 markerDirection,
    @JsonProperty("arcade_marker_size") float arcadeMarkerSize,
    @JsonProperty("spg_marker_params") float[] spgMarkerParams
) {}
