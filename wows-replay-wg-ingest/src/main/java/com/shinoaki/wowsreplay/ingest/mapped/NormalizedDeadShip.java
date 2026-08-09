package com.shinoaki.wowsreplay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化沉船记录（映射层输出）。受害者用 {@code metaId}（全局一致），
 * 位置 (x,z) 保留给 minimap 标记。
 */
public record NormalizedDeadShip(
    @JsonProperty("clock") float clock,
    @JsonProperty("victim_meta_id") long victimMetaId,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z
) {}
