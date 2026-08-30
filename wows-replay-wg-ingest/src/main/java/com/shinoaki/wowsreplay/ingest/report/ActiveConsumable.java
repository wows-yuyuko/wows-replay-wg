package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 激活的消耗品记录（ActiveConsumable，）。
 */
public record ActiveConsumable(
    @JsonProperty("consumable_id") int consumableId,
    @JsonProperty("duration") float duration,
    @JsonProperty("activated_at") float activatedAt
) {}
