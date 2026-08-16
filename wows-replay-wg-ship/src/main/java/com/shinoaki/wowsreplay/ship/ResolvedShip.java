package com.shinoaki.wowsreplay.ship;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 解析后的战舰实际配置：ship_id + 每个槽位的组件名与组件定义。
 *
 * <p>{@code definition} 是该组件在 wowsinfo ships[ship_id].components 里的完整定义；
 * 组件名在 components 库里找不到（如 {@code *TypeDefault} 空槽）时为 null。</p>
 */
public record ResolvedShip(
    @JsonProperty("ship_id") long shipId,
    @JsonProperty("slots") Map<String, ResolvedSlot> slots
) {
    /** 单个槽位：组件名 + 组件定义（找不到定义时为 null）。 */
    public record ResolvedSlot(
        @JsonProperty("component") String component,
        @JsonProperty("definition") JsonNode definition
    ) {}
}
