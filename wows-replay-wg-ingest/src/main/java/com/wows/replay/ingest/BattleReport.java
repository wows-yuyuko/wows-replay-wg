package com.wows.replay.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.wows.replay.ReplayMeta;
import com.wows.replay.model.GameClock;

import java.util.List;
import java.util.Map;

/**
 * 战斗报告，序列化为 JSON。对标 replay-dumper 的输出结构。
 */
@JsonPropertyOrder({"meta", "summary", "packets", "entities", "vehicles", "vehicles_resolved", "chat", "damage", "minimap"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleReport(

    /** 回放元数据（玩家、地图、版本等） */
    @JsonProperty("meta") MetaSection meta,

    /** 顶层统计摘要 */
    @JsonProperty("summary") SummarySection summary,

    /** 按类型分组的包统计 */
    @JsonProperty("packets") PacketsSection packets,

    /** 实体生命周期事件 */
    @JsonProperty("entities") List<EntityEvent> entities,

    /** 每车辆事件时间线 */
    @JsonProperty("vehicles") List<VehicleTimeline> vehicles,

    /** 聊天消息 */
    @JsonProperty("chat") List<ChatMessage> chat,

    /** 伤害统计 */
    @JsonProperty("damage") DamageSection damage,

    /** 小地图位置时间线 */
    @JsonProperty("minimap") MinimapSection minimap,

    /** 车辆 ID→玩家信息 */
    @JsonProperty("vehicles_resolved") List<ResolvedVehicle> resolvedVehicles
) {

    // ── 子结构 ───────────────────────────────────────────────────────────────

    public record MetaSection(
        @JsonProperty("player_name") String playerName,
        @JsonProperty("player_id") long playerId,
        @JsonProperty("map_name") String mapName,
        @JsonProperty("map_display_name") String mapDisplayName,
        @JsonProperty("game_mode") int gameMode,
        @JsonProperty("game_type") String gameType,
        @JsonProperty("version") String version,
        @JsonProperty("version_xml") String versionXml,
        @JsonProperty("date_time") String dateTime,
        @JsonProperty("duration") int duration,
        @JsonProperty("battle_duration") int battleDuration,
        @JsonProperty("scenario") String scenario,
        @JsonProperty("players_per_team") int playersPerTeam,
        @JsonProperty("teams_count") int teamsCount,
        @JsonProperty("match_group") String matchGroup,
        @JsonProperty("weather_params") Map<String, List<String>> weatherParams,
        @JsonProperty("player_vehicle") String playerVehicle,
        @JsonProperty("vehicles") List<ReplayMeta.VehicleInfoMeta> vehicles
    ) {
        public static MetaSection from(ReplayMeta meta) {
            return new MetaSection(
                meta.playerName(), meta.playerID().value(), meta.mapName(), meta.mapDisplayName(),
                meta.gameMode(), meta.gameType(),
                meta.clientVersionFromExe(), meta.clientVersionFromXml(),
                meta.dateTime(), meta.duration(), meta.battleDuration(),
                meta.scenario(), meta.playersPerTeam(), meta.teamsCount(),
                meta.matchGroup(), meta.weatherParams(), meta.playerVehicle(),
                meta.vehicles());
        }
    }

    public record SummarySection(
        @JsonProperty("total_packets") int totalPackets,
        @JsonProperty("battle_start_clock") float battleStartClock,
        @JsonProperty("total_duration") float totalDuration,
        @JsonProperty("position_packets") int positionPackets,
        @JsonProperty("entity_creates") int entityCreates,
        @JsonProperty("entity_methods") int entityMethods) {}

    public record PacketsSection(
        @JsonProperty("by_type") Map<String, Integer> byType,
        @JsonProperty("unknown_packets") int unknownPackets,
        @JsonProperty("invalid_packets") int invalidPackets) {}

    public record EntityEvent(
        @JsonProperty("clock") float clock,
        @JsonProperty("event") String event,
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("vehicle_id") long vehicleId) {}

    public record VehicleTimeline(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("vehicle_id") long vehicleId,
        @JsonProperty("events") List<Map<String, Object>> events) {}

    public record ChatMessage(
        @JsonProperty("clock") float clock,
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("message") String message) {}

    public record DamageSection(
        @JsonProperty("total_damage_dealt") long totalDamageDealt,
        @JsonProperty("total_damage_received") long totalDamageReceived,
        @JsonProperty("by_weapon") Map<String, Long> byWeapon) {}

    public record MinimapSection(
        @JsonProperty("step") int step,
        @JsonProperty("frames") List<MinimapFrame> frames) {}

    public record MinimapFrame(
        @JsonProperty("clock") float clock,
        @JsonProperty("entities") List<MinimapEntity> entities) {}

    public record MinimapEntity(
        @JsonProperty("entity_id") int entityId,
        @JsonProperty("x") float x,
        @JsonProperty("y") float y,
        @JsonProperty("rotation") float rotation,
        @JsonProperty("team") int team) {}

    public record ResolvedVehicle(
        @JsonProperty("ship_id") long shipId,
        @JsonProperty("relation") int relation,
        @JsonProperty("player_name") String playerName) {}
}
