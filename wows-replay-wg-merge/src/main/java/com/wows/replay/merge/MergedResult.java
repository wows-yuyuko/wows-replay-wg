package com.wows.replay.merge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.ingest.ArtillerySalvo;
import com.wows.replay.ingest.BuffZoneState;
import com.wows.replay.ingest.BuildingState;
import com.wows.replay.ingest.CapturedBuff;
import com.wows.replay.ingest.CapturePointState;
import com.wows.replay.ingest.ChatEvent;
import com.wows.replay.ingest.ConsumableEvent;
import com.wows.replay.ingest.DamageEvent;
import com.wows.replay.ingest.DeadShipRecord;
import com.wows.replay.ingest.KillRecord;
import com.wows.replay.ingest.RibbonEvent;
import com.wows.replay.ingest.ShotHitRecord;
import com.wows.replay.ingest.TeamScore;
import com.wows.replay.ingest.TorpedoRecord;
import com.wows.replay.ingest.VoiceLineEvent;
import com.wows.replay.ingest.WeatherZoneState;
import com.wows.replay.ingest.report.BattleType;
import com.wows.replay.ingest.report.FinishType;
import com.wows.replay.ingest.report.MatchResult;
import com.wows.replay.ingest.report.Player;
import com.wows.replay.model.Recognized;
import com.wows.replay.model.Version;

import java.util.List;
import java.util.Map;

/**
 * 同场次多视角合并结果（结果级合并的去重快照）。
 *
 * <p>由 {@link ReplayMerger} 产出：<b>广播状态直接取主视角</b>（玩家/击杀/队伍比分/控制点/
 * buff 掉落区/天气区域，各视角一致以主视角为权威），其余事件流（聊天/伤害/消耗品/齐射/鱼雷/
 * 命中/语音/沉船/已捕获 Buff/勋带/建筑）跨视角并集后按事件身份去重；顶层元数据与胜负以主视角
 * 为准。事件流按 clock 升序，供时间线消费方直接使用。</p>
 *
 * @see ReplayMerger
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergedResult(

    /** 参与合并的回放份数（含主视角）。 */
    @JsonProperty("replay_count") int replayCount,

    /** 竞技场 id（已校验全部一致）。 */
    @JsonProperty("arena_id") long arenaId,

    /** 客户端版本（主视角）。 */
    @JsonProperty("version") Version version,

    /** 本地化地图名（主视角）。 */
    @JsonProperty("map_name") String mapName,

    /** 本地化场景名（主视角）。 */
    @JsonProperty("game_mode") String gameMode,

    /** 游戏类型（主视角）。 */
    @JsonProperty("game_type") Recognized<BattleType> gameType,

    /** 匹配分组（主视角）。 */
    @JsonProperty("match_group") String matchGroup,

    /** 胜负（主视角）。 */
    @JsonProperty("match_result") MatchResult matchResult,

    /** 结束方式（主视角）。 */
    @JsonProperty("finish_type") Recognized<FinishType> finishType,

    /** 获胜队伍（主视角，-1=平局）。 */
    @JsonProperty("winning_team") Integer winningTeam,

    /** 战斗开始/结果/结束时钟（主视角）。 */
    @JsonProperty("battle_start_clock") Float battleStartClock,
    @JsonProperty("battle_result_clock") Float battleResultClock,
    @JsonProperty("battle_end_clock") Float battleEndClock,

    /** 玩家（主视角广播名册，不做并集）。 */
    @JsonProperty("players") List<Player> players,

    /** 击杀（主视角广播事件，不做并集）。 */
    @JsonProperty("kill_log") List<KillRecord> killLog,

    /** 聊天（按 clock+sender+channel+message 去重）。 */
    @JsonProperty("chat_log") List<ChatEvent> chatLog,

    /** 伤害事件（按 aggressor+victim+clock+amount 去重，docs §10.5 gather_damage_events）。 */
    @JsonProperty("damage_events") List<DamageEvent> damageEvents,

    /** 消耗品激活（按 clock+entity+consumable 去重）。 */
    @JsonProperty("consumable_log") List<ConsumableEvent> consumableLog,

    /** 齐射（按 avatar+salvoId 去重）。 */
    @JsonProperty("fired_salvos") List<ArtillerySalvo> firedSalvos,

    /** 鱼雷（按 owner+shotId 去重）。 */
    @JsonProperty("torpedoes") List<TorpedoRecord> torpedoes,

    /** 炮弹命中（按 shotId 去重）。 */
    @JsonProperty("shot_hits") List<ShotHitRecord> shotHits,

    /** 语音指令（按 clock+sender+message 去重）。 */
    @JsonProperty("voice_line_log") List<VoiceLineEvent> voiceLineLog,

    /** 勋带（跨视角按 clock+ribbonId 去重）。 */
    @JsonProperty("ribbon_log") List<RibbonEvent> ribbonLog,

    /** 沉船（按 victim 去重，取首条）。 */
    @JsonProperty("dead_ships") List<DeadShipRecord> deadShips,

    /** 队伍比分（主视角）。 */
    @JsonProperty("team_scores") List<TeamScore> teamScores,

    /** 控制点（主视角）。 */
    @JsonProperty("capture_points") List<CapturePointState> capturePoints,

    /** Buff 掉落区（主视角）。 */
    @JsonProperty("buff_zones") List<BuffZoneState> buffZones,

    /** 已捕获 Buff（按 entity+clock 去重）。 */
    @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs,

    /** 天气区域（主视角）。 */
    @JsonProperty("weather_zones") List<WeatherZoneState> weatherZones,

    /** 建筑（按 entityId 去重）。 */
    @JsonProperty("buildings") List<BuildingState> buildings,

    /** 原始战报 JSON（主视角）。 */
    @JsonProperty("battle_results") String battleResultsJson,

    /** 去重统计：流名 → 被合并掉（重复）的条数。 */
    @JsonProperty("dedup_stats") Map<String, Integer> dedupStats
) {}
