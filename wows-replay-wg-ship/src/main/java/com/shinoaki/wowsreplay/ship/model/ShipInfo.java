package com.shinoaki.wowsreplay.ship.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * wowsinfo.json 中 {@code ships.<id>} 的一条战舰完整配置。
 *
 * <p>基础字段（name/description/year/regionID/typeID 均为 {@code IDS_...} 键，需经 lang.json 本地化）
 * + 消耗品槽 + 可研发模块树（{@code modules}）+ 组件库（{@code components}，原始 JSON，不参与序列化）。</p>
 */
public record ShipInfo(
        long id,
        String name,
        String description,
        String year,
        boolean paperShip,
        String index,
        int tier,
        String region,
        String type,
        String regionId,
        String typeId,
        String group,
        long costXp,
        long costGold,
        long costCr,
        List<List<ConsumableSlot>> consumables,
        List<Long> nextShips,
        List<String> permoflages,
        List<ModuleSlot> modules,
        @JsonIgnore JsonNode components
) {
    /** 一个消耗品槽位内的可选变体（如 {@code Default} / {@code Default_Gold}）。 */
    public record ConsumableSlot(String name, String type) {}

    /** 特种/绝版船的 group 集合（对齐 libwowsinfo {@code SPECIAL_GROUPS}）。 */
    private static final Set<String> SPECIAL_GROUPS = Set.of(
            "ultimate", "specialUnsellable", "upgradeableUltimate", "upgradeableExclusive",
            "unavailable", "disabled", "preserved", "clan", "earlyAccess",
            "demoWithoutStats", "demoWithStats");

    /** 按组件名取组件定义；不存在时返回 null。 */
    public JsonNode component(String name) {
        return components == null ? null : components.get(name);
    }

    /** 金币船（group == "special"）。 */
    public boolean premium() {
        return "special".equals(group);
    }

    /** 特种/绝版船（命中 SPECIAL_GROUPS）。 */
    public boolean special() {
        return SPECIAL_GROUPS.contains(group);
    }

    /** 按规范化槽位名（如 "hull"）取模块槽；不存在返回 null。 */
    public ModuleSlot moduleSlot(String label) {
        if (modules == null) return null;
        for (ModuleSlot m : modules) {
            if (m.label().equals(label)) return m;
        }
        return null;
    }
}
