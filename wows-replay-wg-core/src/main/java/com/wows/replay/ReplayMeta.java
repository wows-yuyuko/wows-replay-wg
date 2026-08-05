package com.wows.replay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.AccountId;
import com.wows.replay.model.GameParamId;
import com.wows.replay.model.MetaId;

import java.util.List;
import java.util.Map;

/**
 * 从 .wowsreplay 文件开头的 JSON 块解析的元数据。
 * 对标 Rust {@code ReplayMeta} struct.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplayMeta(
    @JsonProperty("matchGroup")
    String matchGroup,

    @JsonProperty("gameMode")
    int gameMode,

    @JsonProperty("gameType")
    String gameType,

    @JsonProperty("clientVersionFromExe")
    String clientVersionFromExe,

    @JsonProperty("scenarioUiCategoryId")
    int scenarioUiCategoryId,

    @JsonProperty("mapDisplayName")
    String mapDisplayName,

    @JsonProperty("mapId")
    int mapId,

    @JsonProperty("clientVersionFromXml")
    String clientVersionFromXml,

    @JsonProperty("weatherParams")
    Map<String, List<String>> weatherParams,

    @JsonProperty("duration")
    int duration,

    @JsonProperty("gameLogic")
    String gameLogic,

    @JsonProperty("name")
    String name,

    @JsonProperty("scenario")
    String scenario,

    @JsonProperty("playerID")
    AccountId playerID,

    @JsonProperty("vehicles")
    List<VehicleInfoMeta> vehicles,

    @JsonProperty("playersPerTeam")
    int playersPerTeam,

    @JsonProperty("dateTime")
    String dateTime,

    @JsonProperty("mapName")
    String mapName,

    @JsonProperty("playerName")
    String playerName,

    @JsonProperty("scenarioConfigId")
    int scenarioConfigId,

    @JsonProperty("teamsCount")
    int teamsCount,

    @JsonProperty("logic")
    String logic,

    @JsonProperty("playerVehicle")
    String playerVehicle,

    @JsonProperty("battleDuration")
    int battleDuration
) {
    /**
     * 元数据中的车辆信息。
     *
     * <p>{@code id} 是竞技场玩家状态 {@code id} 字段（KEY_ID / meta_ship_id）的同空间值，
     * 用来把 meta 车辆映射到战斗内玩家（{@code m.id() == player.meta_ship_id()}），
     * <strong>不是</strong>账号 ID。真正的账号 ID 见 {@code playerID} 与
     * {@code PlayerStateData.dbId()}（accountDBID）。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VehicleInfoMeta(
        @JsonProperty("shipId") GameParamId shipId,
        @JsonProperty("relation") int relation,
        @JsonProperty("id") MetaId id,
        @JsonProperty("name") String name
    ) {}
}
