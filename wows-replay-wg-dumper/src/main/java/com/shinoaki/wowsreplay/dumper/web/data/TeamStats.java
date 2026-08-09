package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单队汇总统计。
 *
 * @param damage    团队总伤害
 * @param hpPool    团队总血池（全员 maxHealth 之和）
 * @param potential 团队总潜在伤害（agro 之和）
 * @param scouting  团队总点亮伤害（scouting_damage 之和）
 */
public record TeamStats(
    @JsonProperty("damage") double damage,
    @JsonProperty("hp_pool") double hpPool,
    @JsonProperty("potential") double potential,
    @JsonProperty("scouting") double scouting
) {}
