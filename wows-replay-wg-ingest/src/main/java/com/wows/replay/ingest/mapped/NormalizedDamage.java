package com.wows.replay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化伤害事件（映射层输出）。aggressor/victim 用 {@code metaId}（全局一致），
 * 跨视角按 metaId+clock+amount 去重不会因录制者实体 id ±1 而漏重。
 */
public record NormalizedDamage(
    @JsonProperty("clock") float clock,
    @JsonProperty("aggressor_meta_id") long aggressorMetaId,
    @JsonProperty("victim_meta_id") long victimMetaId,
    @JsonProperty("amount") float amount
) {}
