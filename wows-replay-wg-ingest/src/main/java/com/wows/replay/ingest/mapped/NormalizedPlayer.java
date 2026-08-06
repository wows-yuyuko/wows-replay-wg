package com.wows.replay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化玩家（映射层输出，docs 目标：最终输出以 {@code metaId} 为主、{@code accountId} 保留在玩家信息）。
 *
 * <p>去掉视角相关的 {@code entity_id}——各视角录制者自身实体 id 会差 ±1（见 metaPlayers 一致性测试），
 * 而 {@code metaId}（战斗内 meta id）与 {@code accountId}（accountDBID）跨视角全局一致。</p>
 *
 * @param metaId    战斗内 meta id（全局一致主键）
 * @param accountId 账号 ID（accountDBID，保留在用户信息里；bot/未知回退为 metaId 本身）
 * @param username  玩家名
 * @param teamId    队伍 id（1/2）
 * @param relation  0=自己, 1=同队, 2=敌方（相对本视角录制者）
 * @param isBot     是否 bot
 */
public record NormalizedPlayer(
    @JsonProperty("meta_id") long metaId,
    @JsonProperty("account_id") long accountId,
    @JsonProperty("username") String username,
    @JsonProperty("team_id") int teamId,
    @JsonProperty("relation") int relation,
    @JsonProperty("is_bot") boolean isBot
) {}
