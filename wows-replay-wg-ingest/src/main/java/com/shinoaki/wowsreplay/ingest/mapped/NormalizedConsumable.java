package com.shinoaki.wowsreplay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化消耗品激活记录（映射层输出）。使用者用 {@code metaId}（全局一致），
 * 去掉视角相关的 {@code entity_id}（消耗品使用者实体 id 各视角可能 ±1）。
 */
public record NormalizedConsumable(
    @JsonProperty("clock") float clock,
    @JsonProperty("meta_id") long metaId,
    @JsonProperty("username") String username,
    @JsonProperty("consumable_id") long consumableId,
    @JsonProperty("duration") float duration
) {}
