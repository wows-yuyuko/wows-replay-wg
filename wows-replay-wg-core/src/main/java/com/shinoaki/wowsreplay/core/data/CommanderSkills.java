package com.shinoaki.wowsreplay.core.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 舰长已学技能（对标 Rust {@code CrewModifiersCompactParams.learnedSkills} 的 {@code Skills}）。
 *
 * <p>0.10.0+ 舰长技能重做后按舰种各一组 skill-type id（原始 id，不解析技能名）；
 * 0.9.x 之前是 u64 位掩码，解码后应用到全部舰种（位 i 表示 skill type i+1）。
 * 始终输出全部 6 个舰种键（空数组保留），对齐 Rust {@code Skills} 序列化。</p>
 */
public record CommanderSkills(
    @JsonProperty("aircraft_carrier") List<Integer> aircraftCarrier,
    @JsonProperty("battleship") List<Integer> battleship,
    @JsonProperty("cruiser") List<Integer> cruiser,
    @JsonProperty("destroyer") List<Integer> destroyer,
    @JsonProperty("auxiliary") List<Integer> auxiliary,
    @JsonProperty("submarine") List<Integer> submarine
) {}
