package com.shinoaki.wowsreplay.core.data;

import com.shinoaki.wowsreplay.core.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * wowsinfo.json 解析结果：GameParams 原始 id → 名称映射，供 dumper vehicle 节点数组富化。
 *
 * <p>wowsinfo.json 由外部工具生成，位于 {@code <live>/app/data/wowsinfo.json}，结构如下
 * （各条目以内部名作键，值里带 {@code id}（GameParam id）与 {@code icon}；本类反转为 id → 名称）：</p>
 */
public record WowsInfo(
        Map<Long, String> shipType,
        Map<Long, String> modernizations,
        Map<Long, String> consumables,
        /** 外观：id → {icon, type}（type 如 "MSkin" 等）。 */
        Map<Long, ExteriorInfo> exteriors,
        /** skillType id → 技能名（commander_skills 的 skill-type id 用，取条目内部名）。 */
        Map<Integer, String> skills,
        /** 消耗品 GameParams id → consumableType（onConsumableUsed 的 b[1] 用它解析；wowsinfo 缺该字段时为空）。 */
        Map<Long, Integer> abilityConsumableType
) {

    public static final WowsInfo EMPTY =
            new WowsInfo(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    /** 外观条目：icon + type（wowsinfo exteriors 段）。 */
    public record ExteriorInfo(String icon, String type) {}

    public String modernization(long id) {
        return modernizations.get(id);
    }

    public String consumable(long id) {
        return consumables.get(id);
    }

    public ExteriorInfo exterior(long id) {
        return exteriors.get(id);
    }

    /** 战舰类型（"AirCarrier"/"Battleship"/"Destroyer"/"Cruiser"/"Submarine"/"Auxiliary"），未知返回 null。 */
    public String shipType(long shipId) {
        return shipType.get(shipId);
    }

    public String skill(int skillType) {
        return skills.get(skillType);
    }

    /** 消耗品 consumableType（onConsumableUsed b[1]）；未知返回 null。 */
    public Integer consumableTypeOf(long abilityId) {
        return abilityConsumableType.get(abilityId);
    }

    /** 解析 wowsinfo.json 文本。缺失/解析异常由调用方兜底。 */
    public static WowsInfo fromJson(String json) {
        var root = JsonMapper.readTree(json);

        return new WowsInfo(
                shipsTypeMap(root),
                idNameMap(root, "modernizations"),
                idNameMap(root, "abilities"),
                exteriorMap(root),
                skillTypeMap(root),
                consumableTypeMap(root));
    }

    /** 内部名 → {id, name} 段落 → id → name 映射（未知 id / 空名跳过）。 */
    private static Map<Long, String> idNameMap(JsonNode root, String section) {
        var out = new HashMap<Long, String>();
        for (var e : root.path(section).properties()) {
            long id = e.getValue().path("id").asLong();
            String name = e.getValue().path("icon").asString();
            if (id != 0 && !name.isBlank()) out.put(id, name);
        }
        return out;
    }

    /** skills 段：skillType → name（同 skillType 取首个非空）。 */
    private static Map<Integer, String> skillTypeMap(JsonNode root) {
        var out = new HashMap<Integer, String>();
        for (var e : root.path("skills").properties()) {
            int type = e.getValue().path("skillType").asInt();
            String name = e.getKey();
            if (type > 0 && !name.isBlank()) out.putIfAbsent(type, name);
        }
        return out;
    }

    /** exteriors 段：id → {icon, type}（未知 id / 空 icon 跳过）。 */
    private static Map<Long, ExteriorInfo> exteriorMap(JsonNode root) {
        var out = new HashMap<Long, ExteriorInfo>();
        for (var e : root.path("exteriors").properties()) {
            long id = e.getValue().path("id").asLong();
            String icon = e.getValue().path("icon").asString();
            String type = e.getValue().path("type").asString();
            if (id != 0 && !icon.isBlank()) out.put(id, new ExteriorInfo(icon, type));
        }
        return out;
    }

    /** abilities 段：消耗品 id → consumableType（wowsinfo 未提供该字段时返回空表）。 */
    private static Map<Long, Integer> consumableTypeMap(JsonNode root) {
        var out = new HashMap<Long, Integer>();
        for (var e : root.path("abilities").properties()) {
            long id = e.getValue().path("id").asLong();
            var ct = e.getValue().path("consumableType");
            if (id != 0 && ct.isIntegralNumber()) out.put(id, ct.asInt());
        }
        return out;
    }

    private static Map<Long, String> shipsTypeMap(JsonNode root) {
        var out = new HashMap<Long, String>();
        for (var e : root.path("ships").properties()) {

            long shipId = Long.parseLong(e.getKey());
            var type = e.getValue().path("type").asString();
            out.putIfAbsent(shipId, type);
        }
        return out;
    }
}
