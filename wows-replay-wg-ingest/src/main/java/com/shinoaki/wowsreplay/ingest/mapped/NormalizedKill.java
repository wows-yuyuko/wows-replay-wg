package com.shinoaki.wowsreplay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化击杀记录（映射层输出）。所有玩家身份用 {@code metaId}（全局一致），不带视角相关实体 id。
 */
public record NormalizedKill(
    @JsonProperty("clock") float clock,
    @JsonProperty("killer_meta_id") long killerMetaId,
    @JsonProperty("killer_name") String killerName,
    @JsonProperty("victim_meta_id") long victimMetaId,
    @JsonProperty("victim_name") String victimName,
    @JsonProperty("cause") int cause
) {}
