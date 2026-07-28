package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 3D rotation: yaw, pitch, roll (f32 × 3).
 */
public record Rot3(
    @JsonProperty("yaw") float yaw,
    @JsonProperty("pitch") float pitch,
    @JsonProperty("roll") float roll
) {

    public static final Rot3 ZERO = new Rot3(0f, 0f, 0f);
}
