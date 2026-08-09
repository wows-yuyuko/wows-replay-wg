package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 战斗时间线（累计伤害 + 团队差距），由 {@code BattleTimelineCalculator} 基于小地图
 * 流式处理数据（{@code damage_events / shot_hits / frames / dead_ships}）计算。
 *
 * @param duration 正赛时长（秒）
 * @param minSec   时间轴起点（秒）
 * @param maxSec   时间轴终点（秒）
 * @param players  每玩家累计伤害：account id → 逐秒累计点
 * @param teams    每队时间线：team id → {@link TeamTimeline}
 * @param gap      团队差距：team0 − team1
 */
public record BattleTimeline(
    @JsonProperty("duration") double duration,
    @JsonProperty("min_sec") int minSec,
    @JsonProperty("max_sec") int maxSec,
    @JsonProperty("players") Map<String, List<TimelinePoint>> players,
    @JsonProperty("teams") Map<String, TeamTimeline> teams,
    @JsonProperty("gap") TeamGap gap
) {}
