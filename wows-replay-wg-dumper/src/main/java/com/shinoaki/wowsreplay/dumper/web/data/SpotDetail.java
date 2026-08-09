package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对单个目标的点亮伤害。
 *
 * @param scoutingDamage 对该目标造成的点亮伤害（scouting_damage）
 */
public record SpotDetail(
    @JsonProperty("scouting_damage") double scoutingDamage
) {}
