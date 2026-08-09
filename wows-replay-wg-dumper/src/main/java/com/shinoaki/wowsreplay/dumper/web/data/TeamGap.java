package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 团队差距时间线（方向：team0 − team1，正数 = team0 领先）。
 *
 * @param healthGap 血池百分比差距（%），起点 (0,0)
 * @param scoreGap  分数差距，起点 (0,0)
 */
public record TeamGap(
    @JsonProperty("health_gap") List<TimelinePoint> healthGap,
    @JsonProperty("score_gap") List<TimelinePoint> scoreGap
) {}
