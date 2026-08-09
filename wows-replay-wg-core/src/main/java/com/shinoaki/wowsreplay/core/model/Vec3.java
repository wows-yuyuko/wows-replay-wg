package com.shinoaki.wowsreplay.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 3D 向量（f32 × 3).
 */
public record Vec3(
    @JsonProperty("x") float x,
    @JsonProperty("y") float y,
    @JsonProperty("z") float z
) {

    public static final Vec3 ZERO = new Vec3(0f, 0f, 0f);
}
