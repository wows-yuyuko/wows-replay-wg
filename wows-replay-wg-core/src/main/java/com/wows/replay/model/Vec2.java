package com.wows.replay.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 2D 向量（f32 × 2).
 * 对标 Rust {@code wowsunpack::game_types::Vec2}.
 */
public record Vec2(
    @JsonProperty("x") float x,
    @JsonProperty("y") float y
) {

    public static final Vec2 ZERO = new Vec2(0f, 0f);
}
