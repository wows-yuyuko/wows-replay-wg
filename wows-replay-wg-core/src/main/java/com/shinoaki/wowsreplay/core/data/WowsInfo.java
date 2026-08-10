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
        Map<Long, String> exteriors,
        /** skillType id → 技能名（commander_skills 的 skill-type id 用，取条目内部名）。 */
        Map<Integer, String> skills
) {

    public static final WowsInfo EMPTY = new WowsInfo(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public String modernization(long id) {
        return modernizations.get(id);
    }

    public String consumable(long id) {
        return consumables.get(id);
    }

    public String exterior(long id) {
        return exteriors.get(id);
    }

    /** 战舰类型（"AirCarrier"/"Battleship"/"Destroyer"/"Cruiser"/"Submarine"/"Auxiliary"），未知返回 null。 */
    public String shipType(long shipId) {
        return shipType.get(shipId);
    }

    public String skill(int skillType) {
        return skills.get(skillType);
    }

    /** 解析 wowsinfo.json 文本。缺失/解析异常由调用方兜底。 */
    public static WowsInfo fromJson(String json) {
        var root = JsonMapper.readTree(json);

        return new WowsInfo(
                shipsTypeMap(root),
                idNameMap(root, "modernizations"),
                idNameMap(root, "abilities"),
                idNameMap(root, "exteriors"),
                skillTypeMap(root));
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
