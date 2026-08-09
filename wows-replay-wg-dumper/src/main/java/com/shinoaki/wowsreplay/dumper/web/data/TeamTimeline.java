package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单队时间线数据。
 *
 * @param initialHp 队伍初始总血量（全员 maxHealth 之和）
 * @param damage    累计伤害（对齐统一 bucket 时间轴）
 * @param score     队伍分数（对齐统一 bucket 时间轴）
 * @param health    每帧剩余血量（帧时钟，未分桶）
 */
public record TeamTimeline(
    @JsonProperty("initial_hp") double initialHp,
    @JsonProperty("damage") List<TimelinePoint> damage,
    @JsonProperty("score") List<TimelinePoint> score,
    @JsonProperty("health") List<TimelinePoint> health
) {}
