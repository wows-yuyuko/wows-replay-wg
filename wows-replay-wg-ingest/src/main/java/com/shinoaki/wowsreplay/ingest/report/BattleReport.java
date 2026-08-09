package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Recognized;
import com.shinoaki.wowsreplay.core.model.Version;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 战斗结束快照（对标 Rust {@code BattleWorld::into_report()} 的输出 {@code BattleReport}，
 * report.rs §3）。
 *
 * <p>一次性、消费式装配的结果：所有字段都是独立拥有的拷贝，与实时可变状态解耦。</p>
 *
 * @see BattleReportBuilder
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleReport(

    /** 竞技场 ID，默认 0。 */
    @JsonProperty("arena_id") long arenaId,

    /** 录制者玩家（必须存在，见 §7.3）。 */
    @JsonProperty("self_player") Player selfPlayer,

    /** 客户端版本（从 clientVersionFromExe 解析）。 */
    @JsonProperty("version") Version version,

    /** 本地化地图名。 */
    @JsonProperty("map_name") String mapName,

    /** 本地化场景名。 */
    @JsonProperty("game_mode") String gameMode,

    /** 游戏类型。 */
    @JsonProperty("game_type") Recognized<BattleType> gameType,

    /** 匹配分组。 */
    @JsonProperty("match_group") String matchGroup,

    /** 全部玩家（含 self_player），每个都带 VehicleEntity。 */
    @JsonProperty("players") List<Player> players,

    /** 聊天记录（到达顺序）。 */
    @JsonProperty("game_chat") List<GameMessage> gameChat,

    /** 原始战报（已用 constants.json 解析为具名对象；无 battle_results 数据包时为 null）。 */
    @JsonProperty("battle_results") JsonNode battleResults,

    /** key为metaId 击杀者 → 死亡记录（按击杀者排序，确定性）。 */
    @JsonProperty("frags") Map<Long, List<DeathInfo>> frags,

    /** Win/Loss/Draw。 */
    @JsonProperty("match_result") MatchResult matchResult,

    /** 结束方式。 */
    @JsonProperty("finish_type") Recognized<FinishType> finishType,

    /** 控制点（按索引序）。 */
    @JsonProperty("capture_points") List<CapturePointState> capturePoints,

    /** 军备竞赛掉落区。 */
    @JsonProperty("buff_zones") Map<EntityId, BuffZoneState> buffZones,

    /** 已捕获 Buff（到达顺序）。 */
    @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs,

    /** 队伍比分（按队伍索引序）。 */
    @JsonProperty("team_scores") List<TeamScore> teamScores,

    /** 海岸炮台/堡垒等。 */
    @JsonProperty("buildings") List<BuildingEntity> buildings,

    /** 局部天气区域（创建顺序）。 */
    @JsonProperty("local_weather_zones") List<LocalWeatherZone> localWeatherZones,

    /** 战斗开始时钟（正赛进入 Waiting 时）。 */
    @JsonProperty("battle_start_clock") float battleStartClock,

    /** 自我玩家按武器伤害（全部类别）。 */
    @JsonProperty("self_damage_stats") List<DamageStatEntry> selfDamageStats,

    /** 全船消耗品激活记录。 */
    @JsonProperty("active_consumables") Map<EntityId, List<ActiveConsumable>> activeConsumables,

    /** 元数据 duration（最大时长，秒）。 */
    @JsonProperty("max_duration") long maxDuration,

    /** 正赛时长。 */
    @JsonProperty("played_duration") float playedDuration,

    /** 正赛结束后到最后一包的时间。 */
    @JsonProperty("extra_duration") float extraDuration
) {}
