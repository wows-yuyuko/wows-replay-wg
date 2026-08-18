package com.shinoaki.wowsreplay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.Recognized;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.ingest.*;
import com.shinoaki.wowsreplay.ingest.report.BattleType;
import com.shinoaki.wowsreplay.ingest.report.FinishType;
import com.shinoaki.wowsreplay.ingest.report.MatchResult;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 规范化回放数据（映射层输出，docs 目标：最终输出以 {@code metaId} 为主、{@code accountId} 保留在玩家信息）。
 *
 * <p>由 {@link ReplayMapper} 在 {@code BattleWorld} 处理完成后生成，把视角相关的实体 id
 * 统一解析为全局一致的 {@code metaId}；单视角输出与多视角合并共享同一份规范化结构。
 * 流式合并（{@code MergedSession}）也复用本层做归一。</p>
 *
 * <p><b>id 策略</b>：玩家事件流（击杀/伤害/聊天/消耗品/沉船）只用 {@code metaId}；
 * 玩家信息带 {@code metaId} + {@code accountId}；非玩家实体（建筑/控制点/buff 区/天气区）
 * 保留 {@code entityId}（它们跨视角本就一致）；已捕获 Buff 由队伍捕获（{@code teamId}，本就全局）；
 * 齐射/鱼雷/命中的身份用全局唯一的 {@code salvoId}/{@code shotId}（不依赖视角相关实体 id）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedReplay(
    // ── 元数据 ──
    @JsonProperty("arena_id") long arenaId,
    @JsonProperty("version") Version version,
    @JsonProperty("map_name") String mapName,
    @JsonProperty("game_mode") String gameMode,
    @JsonProperty("game_type") Recognized<BattleType> gameType,
    @JsonProperty("match_group") String matchGroup,
    @JsonProperty("match_result") MatchResult matchResult,
    @JsonProperty("finish_type") Recognized<FinishType> finishType,
    @JsonProperty("winning_team") int winningTeam,
    @JsonProperty("battle_start_clock") float battleStartClock,
    @JsonProperty("battle_result_clock") float battleResultClock,
    @JsonProperty("battle_end_clock") float battleEndClock,
    @JsonProperty("battle_results") JsonNode battleResultsJson,

    // ── 玩家（metaId + accountId，accountId 保留在用户信息）──
    @JsonProperty("players") List<NormalizedPlayer> players,

    // ── 玩家事件流（metaId 主键）──
    @JsonProperty("kill_log") List<NormalizedKill> killLog,
    @JsonProperty("damage_events") List<NormalizedDamage> damageEvents,
    @JsonProperty("chat_log") List<NormalizedChat> chatLog,
    @JsonProperty("consumable_log") List<NormalizedConsumable> consumableLog,
    @JsonProperty("dead_ships") List<NormalizedDeadShip> deadShips,
    @JsonProperty("voice_line_log") List<VoiceLineEvent> voiceLineLog,
    @JsonProperty("ribbon_log") List<RibbonEvent> ribbonLog,

    // ── 稳定 id 流（身份=salvoId/shotId，全局唯一）──
    @JsonProperty("fired_salvos") List<ArtillerySalvo> firedSalvos,
    @JsonProperty("torpedoes") List<TorpedoRecord> torpedoes,
    @JsonProperty("shot_hits") List<ShotHitRecord> shotHits,

    // ── 非玩家实体状态（保留 entityId，跨视角一致）──
    @JsonProperty("team_scores") List<TeamScore> teamScores,
    @JsonProperty("capture_points") List<CapturePointState> capturePoints,
    @JsonProperty("buff_zones") List<BuffZoneState> buffZones,
    @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs,
    @JsonProperty("weather_zones") List<WeatherZoneState> weatherZones,
    @JsonProperty("buildings") List<BuildingState> buildings
) {}
