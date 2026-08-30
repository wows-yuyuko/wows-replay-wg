package com.shinoaki.wowsreplay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 控制点状态（CapturePointState， CapturePointOrder）。
 */
public record CapturePointState(
    @JsonProperty("index") int index,
    @JsonProperty("team_id") long teamId,
    @JsonProperty("invader_team") long invaderTeam,
    @JsonProperty("has_invaders") boolean hasInvaders,
    @JsonProperty("both_inside") boolean bothInside,
    @JsonProperty("is_enabled") boolean isEnabled,
    @JsonProperty("progress") float progress,
    @JsonProperty("x") float x,
    @JsonProperty("z") float z,
    @JsonProperty("radius") float radius
) {
    /** 缺索引位置的默认占位（§7.4）。 */
    public static CapturePointState empty(int index) {
        return new CapturePointState(index, -1, -1, false, false, true, 0f, 0f, 0f, 0f);
    }
}
