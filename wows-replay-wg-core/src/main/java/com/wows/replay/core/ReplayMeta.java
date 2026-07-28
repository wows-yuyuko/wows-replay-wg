package com.wows.replay.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.AccountId;
import com.wows.replay.spec.types.GameParamId;

import java.util.List;
import java.util.Map;

/**
 * Replay metadata parsed from the JSON block at the start of a .wowsreplay file.
 * Mirrors Rust's {@code ReplayMeta} struct.
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
     * Vehicle info within replay metadata.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VehicleInfoMeta(
        @JsonProperty("shipId") GameParamId shipId,
        @JsonProperty("relation") int relation,
        @JsonProperty("id") AccountId id,
        @JsonProperty("name") String name
    ) {}
}
