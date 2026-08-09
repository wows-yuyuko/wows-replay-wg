package com.shinoaki.wowsreplay.dumper.web.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 单个玩家的战斗统计。
 *
 * @param teamId       所属队伍
 * @param damage       个人总伤害
 * @param received     个人承受伤害
 * @param scouting     个人点亮伤害
 * @param potential    个人潜在伤害
 * @param kills        击沉数
 * @param maxHealth    最大血量
 * @param attack       打了谁：目标 account id → {@link AttackDetail}
 * @param receivedFrom 被谁打了：攻击者 account id → {@link ReceivedDetail}
 * @param spotted      点亮了谁：目标 account id → {@link SpotDetail}
 */
public record PlayerStats(
    @JsonProperty("team_id") int teamId,
    @JsonProperty("damage") double damage,
    @JsonProperty("received") double received,
    @JsonProperty("scouting") double scouting,
    @JsonProperty("potential") double potential,
    @JsonProperty("kills") double kills,
    @JsonProperty("max_health") double maxHealth,
    @JsonProperty("attack") Map<String, AttackDetail> attack,
    @JsonProperty("received_from") Map<String, ReceivedDetail> receivedFrom,
    @JsonProperty("spotted") Map<String, SpotDetail> spotted
) {}
