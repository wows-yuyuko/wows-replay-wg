package com.shinoaki.wowsreplay.ship;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 回放 shipConfig blob（{@link com.shinoaki.wowsreplay.core.data.ShipConfig}）解析后的本地化配置。
 *
 * <p>有 id -> 名称映射的槽位（modernizations/consumables/exteriors/commander_skills）输出
 * {@link NamedItem}；无映射的槽位（units/ensigns/ecoboosts）保留原始 id。</p>
 */
public record ResolvedShipConfig(
        @JsonProperty("ship_id") long shipId,
        @JsonProperty("ship_index") @JsonInclude(JsonInclude.Include.NON_NULL) String shipIndex,
        @JsonProperty("ship_name") String shipName,
        @JsonProperty("modernizations") List<NamedItem> modernizations,
        @JsonProperty("consumables") List<NamedItem> consumables,
        @JsonProperty("exteriors") List<NamedItem> exteriors,
        @JsonProperty("units") List<NamedItem> units,
        @JsonProperty("commander_skills") List<NamedItem> commanderSkills,
        @JsonProperty("ensigns") List<Long> ensigns,
        @JsonProperty("ecoboosts") List<Long> ecoboosts
) {
    /** 具名条目：原始 id + 本地化名称 + 图标（名称未知时为 null）。 */
    public record NamedItem(
            @JsonProperty("id") String id,
            @JsonProperty("name") @JsonInclude(JsonInclude.Include.NON_NULL) String name,
            @JsonProperty("icon") @JsonInclude(JsonInclude.Include.NON_NULL) String icon
    ) {}
}
