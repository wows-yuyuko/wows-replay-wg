package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 对单个目标（或单个攻击者）的伤害明细，精确到各武器与命中数。
 *
 * @param total           该目标/攻击者的总伤害（仅统计武器白名单，与 web 端 totalDamage 口径一致）
 * @param damage          各武器造成/受到伤害明细（damage_*），值可能为 null
 * @param hits            各武器命中数明细（hits_*），值可能为 null
 * @param scoutingDamage  对该目标造成的点亮伤害（scouting_damage），可能为 null
 * @param planesKilled    击落该目标的飞机数（planes_killed），可能为 null
 * @param planesKilledByShip 击落该目标飞机中由本体击落数（planes_killed_by_ship），可能为 null
 * @param shipKilled      是否击沉该目标（ship_killed），可能为 null
 */
public record AttackDetail(
    @JsonProperty("total") double total,
    @JsonProperty("damage") Map<String, Number> damage,
    @JsonProperty("hits") Map<String, Number> hits,
    @JsonProperty("scouting_damage") Number scoutingDamage,
    @JsonProperty("planes_killed") Number planesKilled,
    @JsonProperty("planes_killed_by_ship") Number planesKilledByShip,
    @JsonProperty("ship_killed") Number shipKilled
) {}
