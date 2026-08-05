package com.wows.replay.dumper.minimap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Minimap 数据提取输出（对标 Rust {@code replay-dumper::position::MinimapOutput}，
 * docs/replay-dumper-minimap.md §6）。
 *
 * <p>仅覆盖 Single 路径（单回放 ECS）：逐时钟边界事件流 + step 抽稀帧 + 终局状态。
 * 多视角合并（Full/Fast）与顶层 JSON 装配（pipeline）暂不实现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MinimapOutput(
    /** 第一个通过校验的 arena id。 */
    @JsonProperty("arena_id") Long arenaId,
    /** 位置时间线（step 抽稀）。 */
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

    /** 归一化坐标船位（x/y ∈ [-1.5, 1.5]，heading 度）。 */
    public record MinimapEntity(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("y") float y,
        @JsonProperty("heading") float heading,
        @JsonProperty("visible") boolean visible,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("health") float health,
        @JsonProperty("max_health") float maxHealth,
        @JsonProperty("is_alive") boolean isAlive
    ) {}

    public record PlaneEntry(
        @JsonProperty("plane_id") long planeId,
        @JsonProperty("owner_entity_id") int ownerEntityId,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z
    ) {}

    public record TorpedoEntry(
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("owner_id") long ownerId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("origin") com.wows.replay.model.Vec3 origin,
        @JsonProperty("direction") com.wows.replay.model.Vec3 direction,
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
        @JsonProperty("owner_id") int ownerId,
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
        @JsonProperty("aggressor") int aggressor,
        @JsonProperty("victim") int victim,
        @JsonProperty("amount") float amount
    ) {}

    /** 齐射单发炮弹详情。 */
    public record ShotDetail(
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("origin") com.wows.replay.model.Vec3 origin,
        @JsonProperty("pitch") float pitch,
        @JsonProperty("speed") float speed,
        @JsonProperty("target") com.wows.replay.model.Vec3 target,
        @JsonProperty("gun_barrel_id") int gunBarrelId,
        @JsonProperty("server_time_left") float serverTimeLeft,
        @JsonProperty("shooter_height") float shooterHeight,
        @JsonProperty("hit_distance") float hitDistance
    ) {}

    public record ShotEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("avatar_id") int avatarId,
        @JsonProperty("owner_id") long ownerId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("fired_at") float firedAt,
        @JsonProperty("shots") List<ShotDetail> shots
    ) {}

    public record ShotHitEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("owner_id") long ownerId,
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("hit_type") int hitType,
        @JsonProperty("position") com.wows.replay.model.Vec3 position,
        @JsonProperty("terminal_ballistics") com.wows.replay.decode.DecodedPayload.TerminalBallistics terminalBallistics,
        @JsonProperty("fired_at") Float firedAt,
        @JsonProperty("victim_position") com.wows.replay.model.Vec3 victimPosition
    ) {}

    /** 沉船；alt 视角无坐标时 x/z 可为 null。 */
    public record DeadShip(
        @JsonProperty("clock") float clock,
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("x") Float x,
        @JsonProperty("z") Float z
    ) {}

    public record CapturedBuff(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("captured_by") int capturedBy,
        @JsonProperty("clock") float clock
    ) {}
}
