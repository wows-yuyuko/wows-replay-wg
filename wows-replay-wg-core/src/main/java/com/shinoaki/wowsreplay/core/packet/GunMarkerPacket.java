package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.Vec3;

/**
 * 0x18: 枪标/瞄准状态(每 tick, 52 字节)。 */
public record GunMarkerPacket(
    @JsonProperty("target_point") Vec3 targetPoint,
    @JsonProperty("diameter") float diameter,
    @JsonProperty("marker_position") Vec3 markerPosition,
    @JsonProperty("marker_direction") Vec3 markerDirection,
    @JsonProperty("arcade_marker_size") float arcadeMarkerSize,
    @JsonProperty("spg_marker_params") float[] spgMarkerParams
) {}
