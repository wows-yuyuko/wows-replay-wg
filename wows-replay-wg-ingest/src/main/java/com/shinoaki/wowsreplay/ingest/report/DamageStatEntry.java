package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 伤害统计条目（对标 Rust {@code DamageStatEntry}，report.rs §5.1）。
 */
public record DamageStatEntry(
    @JsonProperty("weapon") long weapon,
    @JsonProperty("category") DamageStatCategory category,
    @JsonProperty("count") long count,
    @JsonProperty("total") double total
) {}
