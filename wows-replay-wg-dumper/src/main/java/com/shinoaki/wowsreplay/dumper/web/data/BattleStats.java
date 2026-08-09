package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 战斗统计（团队汇总 + 个人统计），由 {@code BattleStatsCalculator} 计算。
 *
 * @param teams   每队 {@link TeamStats}
 * @param players 每人 {@link PlayerStats}，key 为 account id
 */
public record BattleStats(
    @JsonProperty("teams") Map<String, TeamStats> teams,
    @JsonProperty("players") Map<String, PlayerStats> players
) {}
