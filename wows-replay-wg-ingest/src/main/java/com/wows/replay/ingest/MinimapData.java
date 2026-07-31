package com.wows.replay.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 小地图提取数据结构，对标 Rust {@code position.rs} 中的类型。
 */
public final class MinimapData {

    private MinimapData() {}

    /** 小地图提取的完整输出。 */
    @JsonPropertyOrder({"arena_id", "frames", "firing_events", "damage_events",
        "shot_hits", "dead_ships", "battle_stage", "winning_team", "finish_type",
        "scoring_rules", "captured_buffs"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MinimapOutput(
        @JsonProperty("arena_id") String arenaId,
        @JsonProperty("frames") List<Frame> frames,
        @JsonProperty("firing_events") List<ShotEntry> firingEvents,
        @JsonProperty("damage_events") List<DamageEntry> damageEvents,
        @JsonProperty("shot_hits") List<ShotHitEntry> shotHits,
        @JsonProperty("dead_ships") List<DeadShipEntry> deadShips,
        @JsonProperty("battle_stage") String battleStage,
        @JsonProperty("winning_team") Integer winningTeam,
        @JsonProperty("finish_type") String finishType,
        @JsonProperty("scoring_rules") Object scoringRules,
        @JsonProperty("captured_buffs") List<CapturedBuff> capturedBuffs
    ) {}

    /** 单帧的小地图快照。 */
    @JsonPropertyOrder({"clock", "entities", "planes", "torpedoes", "team_scores",
        "capture_points", "smoke_screens", "buildings", "active_wards", "buff_zones",
        "weather_zones", "time_left"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Frame(
        @JsonProperty("clock") float clock,
        @JsonProperty("entities") List<MinimapEntry> entities,
        @JsonProperty("planes") List<PlaneEntry> planes,
        @JsonProperty("torpedoes") List<TorpedoEntry> torpedoes,
        @JsonProperty("team_scores") List<TeamScore> teamScores,
        @JsonProperty("capture_points") List<CapturePointState> capturePoints,
        @JsonProperty("smoke_screens") List<SmokeScreenEntry> smokeScreens,
        @JsonProperty("buildings") List<BuildingEntry> buildings,
        @JsonProperty("active_wards") List<WardEntry> activeWards,
        @JsonProperty("buff_zones") List<BuffZoneEntry> buffZones,
        @JsonProperty("weather_zones") List<WeatherZoneEntry> weatherZones,
        @JsonProperty("time_left") Float timeLeft
    ) {}

    // ── 实体 / 位置 ──────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MinimapEntry(
        @JsonProperty("id") int id,
        @JsonProperty("x") float x,
        @JsonProperty("y") float y,
        @JsonProperty("heading") float heading,
        @JsonProperty("visible") boolean visible,
        @JsonProperty("visibility_flags") int visibilityFlags,
        @JsonProperty("is_invisible") boolean isInvisible,
        @JsonProperty("last_updated") float lastUpdated,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("health") float health,
        @JsonProperty("max_health") float maxHealth,
        @JsonProperty("is_alive") boolean isAlive
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaneEntry(
        @JsonProperty("plane_id") long planeId,
        @JsonProperty("owner_id") int ownerId,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("last_updated") float lastUpdated
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TorpedoEntry(
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("owner_id") int ownerId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("origin") float[] origin,
        @JsonProperty("direction") float[] direction,
        @JsonProperty("armed") boolean armed,
        @JsonProperty("launched_at") float launchedAt,
        @JsonProperty("updated_at") float updatedAt,
        @JsonProperty("has_maneuver") boolean hasManeuver,
        @JsonProperty("has_acoustic") boolean hasAcoustic
    ) {}

    // ── 射击 / 伤害 ──────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShotEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("avatar_id") int avatarId,
        @JsonProperty("owner_id") int ownerId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("salvo_id") int salvoId,
        @JsonProperty("fired_at") float firedAt,
        @JsonProperty("shots") List<SalvoShotData> shots
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalvoShotData(
        @JsonProperty("origin") float[] origin,
        @JsonProperty("pitch") float pitch,
        @JsonProperty("speed") float speed,
        @JsonProperty("target") float[] target,
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("gun_barrel_id") int gunBarrelId,
        @JsonProperty("server_time_left") float serverTimeLeft,
        @JsonProperty("shooter_height") float shooterHeight,
        @JsonProperty("hit_distance") float hitDistance
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DamageEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("aggressor_id") int aggressorId,
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("amount") float amount
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShotHitEntry(
        @JsonProperty("clock") float clock,
        @JsonProperty("owner_id") int ownerId,
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("shot_id") int shotId,
        @JsonProperty("hit_type") String hitType,
        @JsonProperty("position") float[] position,
        @JsonProperty("terminal_ballistics") Object terminalBallistics,
        @JsonProperty("fired_at") Float firedAt,
        @JsonProperty("victim_position") float[] victimPosition
    ) {}

    // ── 其他对象 ─────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SmokeScreenEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuildingEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("is_alive") boolean isAlive
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WardEntry(
        @JsonProperty("plane_id") long planeId,
        @JsonProperty("owner_id") int ownerId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuffZoneEntry(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius,
        @JsonProperty("team_id") long teamId,
        @JsonProperty("is_active") boolean isActive,
        @JsonProperty("drop_params_id") Long dropParamsId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeatherZoneEntry(
        @JsonProperty("name") String name,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z,
        @JsonProperty("radius") float radius,
        @JsonProperty("params_id") long paramsId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadShipEntry(
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("clock") float clock,
        @JsonProperty("x") Float x,
        @JsonProperty("z") Float z
    ) {}

    // ── 战斗状态 ─────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamScore(
        @JsonProperty("team_index") int teamIndex,
        @JsonProperty("score") long score
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapturePointState(
        @JsonProperty("index") int index,
        @JsonProperty("team_id") long teamId,
        @JsonProperty("invader_team") long invaderTeam,
        @JsonProperty("progress") Object progress,
        @JsonProperty("has_invaders") boolean hasInvaders,
        @JsonProperty("both_inside") boolean bothInside,
        @JsonProperty("is_enabled") boolean isEnabled
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapturedBuff(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("params_id") long paramsId,
        @JsonProperty("captured_by") int capturedBy,
        @JsonProperty("clock") float clock
    ) {}

    // ── 计分规则 ─────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoringRules(
        @JsonProperty("score_limit") Long scoreLimit,
        @JsonProperty("time_limit") Long timeLimit,
        @JsonProperty("victory_condition") String victoryCondition
    ) {}
}
