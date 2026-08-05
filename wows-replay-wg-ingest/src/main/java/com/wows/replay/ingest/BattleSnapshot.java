package com.wows.replay.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 战斗结束快照，由 {@link BattleWorld#intoReport()} 产出。
 *
 * <p>对标 Rust {@code wows-battle-world::BattleWorld::into_report()} 的
 * {@code BattleReport}：一个 owned 的、可序列化的终局状态快照，与实时可变状态
 * {@link BattleWorld} 解耦。字段集对齐 Rust report.rs 的 getter。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleSnapshot(

    @JsonProperty("version") String version,
    @JsonProperty("map_name") String mapName,
    @JsonProperty("arena_id") Long arenaId,
    @JsonProperty("game_mode") int gameMode,
    @JsonProperty("game_type") String gameType,
    @JsonProperty("match_group") String matchGroup,
    @JsonProperty("winning_team") Integer winningTeam,
    @JsonProperty("finish_type") String finishType,
    @JsonProperty("match_result") String matchResult,
    @JsonProperty("max_duration") float maxDuration,
    @JsonProperty("played_duration") Float playedDuration,
    @JsonProperty("extra_duration") Float extraDuration,
    @JsonProperty("battle_start_clock") Float battleStartClock,

    @JsonProperty("players") List<Player> players,
    @JsonProperty("kills") List<Kill> kills,
    @JsonProperty("chat") List<Chat> chat,

    @JsonProperty("damage_events") int damageEvents,
    @JsonProperty("consumables") int consumables,
    @JsonProperty("ribbons") int ribbons,
    @JsonProperty("voice_lines") int voiceLines,
    @JsonProperty("salvos") int salvos,
    @JsonProperty("torpedoes") int torpedoes,
    @JsonProperty("shot_hits") int shotHits,
    @JsonProperty("plane_events") int planeEvents,
    @JsonProperty("active_wards") int activeWards,

    @JsonProperty("capture_points") List<CapturePoint> capturePoints,
    @JsonProperty("buff_zones") int buffZones,
    @JsonProperty("weather_zones") int weatherZones,
    @JsonProperty("buildings") int buildings,
    @JsonProperty("dead_ships") List<DeadShip> deadShips
) {

    public record Player(
        @JsonProperty("db_id") long dbId,
        @JsonProperty("username") String username,
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("team_id") int teamId,
        @JsonProperty("relation") int relation,
        @JsonProperty("is_bot") boolean isBot,
        @JsonProperty("dead") boolean dead,
        @JsonProperty("total_damage") double totalDamage
    ) {}

    public record Kill(
        @JsonProperty("clock") float clock,
        @JsonProperty("killer_entity_id") int killerEid,
        @JsonProperty("killer_name") String killerName,
        @JsonProperty("victim_entity_id") int victimEid,
        @JsonProperty("victim_name") String victimName,
        @JsonProperty("cause") int cause
    ) {}

    public record Chat(
        @JsonProperty("clock") float clock,
        @JsonProperty("sender_db_id") long senderDbId,
        @JsonProperty("sender_name") String senderName,
        @JsonProperty("channel") String channel,
        @JsonProperty("message") String message
    ) {}

    public record CapturePoint(
        @JsonProperty("index") int index,
        @JsonProperty("team_id") long teamId,
        @JsonProperty("invader_team") long invaderTeam,
        @JsonProperty("progress") float progress,
        @JsonProperty("is_enabled") boolean isEnabled,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z
    ) {}

    public record DeadShip(
        @JsonProperty("clock") float clock,
        @JsonProperty("victim_id") int victimId,
        @JsonProperty("x") float x,
        @JsonProperty("z") float z
    ) {}
}
