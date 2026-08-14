package com.shinoaki.wowsreplay.dumper.minimap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.core.model.Vec3;

import java.util.List;

/**
 * Minimap 数据提取输出（对标 Rust {@code replay-dumper::position::MinimapOutput}，
 * docs/replay-dumper-minimap.md §6）。
 *
 * <p>仅覆盖 Single 路径（单回放 ECS）：逐时钟边界事件流 + 逐时钟边界全量帧 + 终局状态。
 * 多视角合并（Full/Fast）与顶层 JSON 装配（pipeline）暂不实现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MinimapOutput(
    /** 第一个通过校验的 arena id。 */
    @JsonProperty("arena_id") Long arenaId,
    /** 位置时间线（逐时钟边界全量帧）。 */
    @JsonProperty("frames") List<MinimapFrame> frames,
    /** 齐射事件流。 */
    @JsonProperty("firing_events") List<ShotEntry> firingEvents,
    /** 伤害事件流。 */
    @JsonProperty("damage_events") List<DamageEntry> damageEvents,
    /** 命中事件流。 */
    @JsonProperty("shot_hits") List<ShotHitEntry> shotHits,
    /** 沉船（位置+时间）。 */
    @JsonProperty("dead_ships") List<DeadShip> deadShips,
    /** 最终战斗阶段名（Waiting/Battle/Results/Finishing/Ended，对齐 Rust Option&lt;String&gt;）。 */
    @JsonProperty("battle_stage") String battleStage,
    /** 获胜队伍 0/1，-1 平局。 */
    @JsonProperty("winning_team") Integer winningTeam,
    /** 结束方式。 */
    @JsonProperty("finish_type") String finishType,
    /** 分数规则。 */
    @JsonProperty("scoring_rules") ScoringRules scoringRules,
    /** 军备竞赛 Buff（到达顺序）。 */
    @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs
) {

    /** 单帧快照（docs §4.2）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MinimapFrame(
        @JsonProperty("clock") float clock,
        @JsonProperty("entities") List<MinimapEntity> entities,
        @JsonProperty("planes") List<PlaneEntry> planes,
        @JsonProperty("torpedoes") List<TorpedoEntry> torpedoes,
        @JsonProperty("smoke_screens") List<SmokeEntry> smokeScreens,
        @JsonProperty("buildings") List<BuildingEntry> buildings,
        @JsonProperty("active_wards") List<WardEntry> activeWards,
        @JsonProperty("buff_zones") List<BuffZoneEntry> buffZones,
        @JsonProperty("weather_zones") List<WeatherZoneEntry> weatherZones,
        @JsonProperty("team_scores") List<TeamScoreEntry> teamScores,
        @JsonProperty("capture_points") List<CapturePointEntry> capturePoints,
        @JsonProperty("time_left") Float timeLeft
    ) {}

    /** 归一化坐标船位（x/y ∈ [-1.5, 1.5]，heading 度）；玩家身份用全局一致 metaId。
     *  {@code side}：敌我分类，0=自己, 1=友军, 2=敌方（单视角相对本视角录制者；合并时相对主视角）。
     *  {@code visible}：该视角小地图上是否有位点；{@code visibility_flags}：服务器权威探测原因掩码
     *  （雷达/水听/目视等，非零=被点亮）；{@code is_invisible}：实体主动隐身态（潜艇下潜/烟雾）。 */
    public record MinimapEntity(
        @JsonProperty("meta_id") long metaId,
        @JsonProperty("x") float x,
        @JsonProperty("y") float y,
        @JsonProperty("heading") float heading,
        @JsonProperty("visible") boolean visible,
        @JsonProperty("visibility_flags") int visibilityFlags,
        @JsonProperty("is_invisible") boolean isInvisible,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("health") float health,
        @JsonProperty("max_health") float maxHealth,
        @JsonProperty("is_alive") boolean isAlive,
        @JsonProperty("side") int side
    ) {}

    public record PlaneEntry(
        @JsonProperty("plane_id") long planeId,
        @JsonProperty("owner_meta_id") long ownerMetaId,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z
    ) {}

    public record TorpedoEntry(
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("owner_meta_id") long ownerMetaId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("origin") Vec3 origin,
        @JsonProperty("direction") Vec3 direction,
        @JsonProperty("armed") boolean armed,
        @JsonProperty("launched_at") float launchedAt,
        @JsonProperty("updated_at") float updatedAt,
        @JsonProperty("has_maneuver") boolean hasManeuver,
        @JsonProperty("has_acoustic") boolean hasAcoustic
    ) {}

    public record SmokeEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius
    ) {}

    public record BuildingEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("is_alive") boolean isAlive
    ) {}

    public record WardEntry(
        @JsonProperty("ward_id") long wardId,
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("owner_meta_id") long ownerMetaId,
        @JsonProperty("x") float x,
        @JsonProperty("y") float y,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius
    ) {}

    public record BuffZoneEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("is_active") boolean isActive
    ) {}

    public record WeatherZoneEntry(
        @JsonProperty("name") String name,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("entity_id") Integer entityId
    ) {}

    public record TeamScoreEntry(
        @JsonProperty("team_index") int teamIndex,
        @JsonProperty("score") long score
    ) {}

    public record CapturePointEntry(
        @JsonProperty("index") int index,
        @JsonProperty("team_id") long teamId,
        @JsonProperty("invader_team") long invaderTeam,
        @JsonProperty("progress") float progress,
        @JsonProperty("is_enabled") boolean isEnabled,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z
    ) {}

    /** 分数规则（BattleLogic state.missions.hold）。 */
    public record ScoringRules(
        @JsonProperty("team_win_score") long teamWinScore,
        @JsonProperty("hold_reward") long holdReward,
        @JsonProperty("hold_period") float holdPeriod,
        @JsonProperty("hold_cp_indices") List<Integer> holdCpIndices
    ) {}

    public record DamageEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("aggressor_meta_id") long aggressorMetaId,
        @JsonProperty("victim_meta_id") long victimMetaId,
        @JsonProperty("amount") float amount
    ) {}

    /** 齐射单发炮弹详情。 */
    public record ShotDetail(
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("origin") Vec3 origin,
        @JsonProperty("pitch") float pitch,
        @JsonProperty("speed") float speed,
        @JsonProperty("target") Vec3 target,
        @JsonProperty("gun_barrel_id") int gunBarrelId,
        @JsonProperty("server_time_left") float serverTimeLeft,
        @JsonProperty("shooter_height") float shooterHeight,
        @JsonProperty("hit_distance") float hitDistance
    ) {}

    public record ShotEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("avatar_meta_id") long avatarMetaId,
        @JsonProperty("owner_meta_id") long ownerMetaId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("fired_at") float firedAt,
        @JsonProperty("shots") List<ShotDetail> shots
    ) {}

    public record ShotHitEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("owner_meta_id") long ownerMetaId,
        @JsonProperty("victim_meta_id") long victimMetaId,
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("hit_type") int hitType,
        @JsonProperty("position") Vec3 position,
        @JsonProperty("terminal_ballistics") DecodedPayload.TerminalBallistics terminalBallistics,
        @JsonProperty("fired_at") Float firedAt,
        @JsonProperty("victim_position") Vec3 victimPosition
    ) {}

    /** 沉船；alt 视角无坐标时 x/z 可为 null。玩家身份用全局一致 metaId。 */
    public record DeadShip(
        @JsonProperty("clock") float clock,
        @JsonProperty("victim_meta_id") long victimMetaId,
        @JsonProperty("x") Float x,
        @JsonProperty("z") Float z
    ) {}

    public record CapturedBuff(
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("clock") float clock
    ) {}

    /**
     * 压缩输出（供外部程序分析）：与 {@link MinimapOutput} 同结构（每帧 planes/torpedoes/
     * smoke_screens/buildings/wards/buff_zones/weather_zones/team_scores/capture_points/time_left
     * 与全部事件流均保留，含可见性 spotting），仅对 frames 的 {@code entities} 做<b>移动增量</b>
     * 压缩：每帧只列出自上一帧以来位置/航向/可见/血量/存活发生<b>显著变化</b>或<b>首次出现</b>的船，
     * 消费方需与上一帧状态合并。由 {@code MinimapExtractor.extractCompressed()} 产生。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Compressed(
        @JsonProperty("movement_delta") boolean movementDelta,
        @JsonProperty("arena_id") Long arenaId,
        @JsonProperty("frames") List<MinimapFrame> frames,
        @JsonProperty("firing_events") List<ShotEntry> firingEvents,
        @JsonProperty("damage_events") List<DamageEntry> damageEvents,
        @JsonProperty("shot_hits") List<ShotHitEntry> shotHits,
        @JsonProperty("dead_ships") List<DeadShip> deadShips,
        @JsonProperty("battle_stage") String battleStage,
        @JsonProperty("winning_team") Integer winningTeam,
        @JsonProperty("finish_type") String finishType,
        @JsonProperty("scoring_rules") ScoringRules scoringRules,
        @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs
    ) {}
}
