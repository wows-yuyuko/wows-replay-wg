package com.shinoaki.wowsreplay.ship.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

/**
 * wowsinfo {@code abilities.<key>} 的一条消耗品定义。
 *
 * <p>以 abilities 段的键为索引（该键同时等于 {@code ship.consumables[][].name} 与 {@code icon}）。</p>
 */
public record AbilityInfo(
        String key,
        long id,
        String nation,
        String name,
        String description,
        String icon,
        String filter,
        String type,
        @JsonIgnore JsonNode abilities,
        @JsonIgnore JsonNode alter
) {
    /** 取指定舰种（或首个）的消耗品参数对象。 */
    public JsonNode paramsFor(String shipType) {
        if (abilities == null || !abilities.isObject()) return null;
        JsonNode node = abilities.get(shipType);
        if (node == null) {
            for (var e : abilities.properties()) {
                node = e.getValue();
                break;
            }
        }
        return node;
    }
}
