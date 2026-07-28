package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.Vec3;

/**
 * Packet 0x18: Gun marker / aiming state (every tick, 52 bytes).
 */
public record GunMarkerPacket(
    @JsonProperty("target_point") Vec3 targetPoint,
    @JsonProperty("diameter") float diameter,
    @JsonProperty("marker_position") Vec3 markerPosition,
    @JsonProperty("marker_direction") Vec3 markerDirection,
    @JsonProperty("arcade_marker_size") float arcadeMarkerSize,
    @JsonProperty("spg_marker_params") float[] spgMarkerParams
) {}
