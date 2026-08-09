package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 被某攻击者造成的伤害明细（reverse of {@link AttackDetail}，不含点亮/击杀标记）。
 *
 * @param total  该攻击者对本舰造成的总伤害
 * @param damage 该攻击者各武器伤害明细（damage_*）
 * @param hits   该攻击者各武器命中数明细（hits_*）
 */
public record ReceivedDetail(
    @JsonProperty("total") double total,
    @JsonProperty("damage") Map<String, Number> damage,
    @JsonProperty("hits") Map<String, Number> hits
) {}
