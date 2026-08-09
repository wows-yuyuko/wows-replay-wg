package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 时间轴单点。
 *
 * @param clock 游戏内时间（秒）
 * @param value 该时刻的数值（累计伤害 / 分数 / 血量 / 差距）
 */
public record TimelinePoint(
    @JsonProperty("clock") double clock,
    @JsonProperty("value") double value
) {}
