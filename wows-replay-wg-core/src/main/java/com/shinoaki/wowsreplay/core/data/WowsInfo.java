package com.shinoaki.wowsreplay.core.data;

import com.shinoaki.wowsreplay.core.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * wowsinfo.json 解析结果：GameParams 原始 id → 名称映射，供 dumper vehicle 节点数组富化。
 *
 * <p>wowsinfo.json 由外部工具生成，位于 {@code <live>/app/data/wowsinfo.json}，结构如下
 * （各条目以内部名作键，值里带 {@code id}（GameParam id）与 {@code icon}；本类反转为 id → 名称）：</p>
 */
public record WowsInfo(
        Map<Long, ShipConfig> shipType,
        Map<Long, Modernizations> modernizations,
        Map<Long, Abilities> consumables,
        /* 外观：id → {icon, type}（type 如 "MSkin" 等）。 */
        Map<Long, ExteriorInfo> exteriors,
        /* skillType id → 技能（icon=内部名，name/description 为 IDS 键，需 lang 翻译）。 */
        Map<Long, Skills> skills,
        /*
         * 飞机
         */
        Map<Long, Aircrafts> aircrafts,
        Map<String, JsonNode> projectiles
) {

    public static final WowsInfo EMPTY =
            new WowsInfo(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    /** 解析 wowsinfo.json 文本。缺失/解析异常由调用方兜底。 */
    public static WowsInfo fromJson(String json) {
        var root = JsonMapper.readTree(json);

        return new WowsInfo(
                shipsTypeMap(root),
                modernizationsMap(root),
                abilitiesMap(root),
                exteriorMap(root),
                skillTypeMap(root),
                aircraftsMap(root),
                projectilesMap(root));
    }

    /** 外观条目：icon + type + name（wowsinfo exteriors 段）。 */
    public record ExteriorInfo(String icon, String type, String name) {
    }

    /** 升级件：name（IDS 键，需 lang 翻译）+ icon。 */
    public record Modernizations(String name, String icon) {
    }

    /** 舰长技能：icon=条目内部名，name/description 为 IDS 键（需 lang 翻译）。 */
    public record Skills(String icon, String name, String description) {
    }

    /** 战舰类型条目：type（"AirCarrier"/"Battleship"/…）。 */
    public record ShipConfig(String type) {
    }

    public record Abilities(String nation, String name, long id, String icon, String filter, String type) {
    }

    /**
     * 飞机
     */
    public record Aircrafts(String key, String type,String nation, long id, String name, String bomName) {

    }

    public Modernizations modernization(long id) {
        return modernizations.get(id);
    }

    public Abilities consumable(long id) {
        return consumables.get(id);
    }

    /** 按 consumableType 名字（constants.json CONSUMABLE_IDS 翻译后的 filter）查第一个匹配能力；无匹配返回 null。 */
    public Abilities consumableFindFilter(String filter) {
        if (filter == null) return null;
        return consumables.values().stream()
                .filter(e -> e.filter() != null && e.filter().equalsIgnoreCase(filter))
                .findFirst().orElse(null);
    }

    public ExteriorInfo exterior(long id) {
        return exteriors.get(id);
    }

    /** 战舰类型（"AirCarrier"/"Battleship"/"Destroyer"/"Cruiser"/"Submarine"/"Auxiliary"），未知返回 null。 */
    public String shipType(long shipId) {
        ShipConfig config = shipType.get(shipId);
        if (config == null) {
            return null;
        }
        return config.type();
    }

    public Skills skill(int skillType) {
        return skills.get((long) skillType);
    }

    private static Map<String, JsonNode> projectilesMap(JsonNode root) {
        var out = new HashMap<String, JsonNode>();
        for (var e : root.path("projectiles").properties()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<Long, Abilities> abilitiesMap(JsonNode root) {
        var out = new HashMap<Long, Abilities>();
        for (var e : root.path("abilities").properties()) {
            long id = e.getValue().path("id").asLong(0L);
            out.put(id, new Abilities(
                    e.getValue().path("nation").asString(),
                    e.getValue().path("name").asString(),
                    id,
                    e.getValue().path("icon").asString(),
                    e.getValue().path("filter").asString(),
                    e.getValue().path("type").asString()
            ));
        }
        return out;
    }

    private static Map<Long, Aircrafts> aircraftsMap(JsonNode root) {
        var out = new HashMap<Long, Aircrafts>();
        for (var e : root.path("aircrafts").properties()) {
            var data = new Aircrafts(e.getKey(), e.getValue().path("type").asString(),
                    e.getValue().path("nation").asString(),
                    e.getValue().path("id").asLong(),
                    e.getValue().path("name").asString(),
                    e.getValue().path("bombName").asString());
            if (data.id() != 0) out.put(data.id(), data);
        }
        return out;
    }

    /** skills 段：skillType → 技能条目（skillType=0 跳过；重复 skillType 取最后一个）。 */
    private static Map<Long, Skills> skillTypeMap(JsonNode root) {
        var out = new HashMap<Long, Skills>();
        for (var e : root.path("skills").properties()) {
            long type = e.getValue().path("skillType").asLong();
            if (type <= 0) continue;
            out.put(type, new Skills(e.getKey(), e.getValue().path("name").asString(),
                    e.getValue().path("description").asString()));
        }
        return out;
    }

    /** exteriors 段：id → {icon, type, name}（未知 id / 空 icon 跳过）。 */
    private static Map<Long, ExteriorInfo> exteriorMap(JsonNode root) {
        var out = new HashMap<Long, ExteriorInfo>();
        for (var e : root.path("exteriors").properties()) {
            long id = e.getValue().path("id").asLong();
            String icon = e.getValue().path("icon").asString();
            String type = e.getValue().path("type").asString();
            String name = e.getValue().path("name").asString();
            if (id != 0 && !icon.isBlank()) out.put(id, new ExteriorInfo(icon, type, name));
        }
        return out;
    }

    private static Map<Long, Modernizations> modernizationsMap(JsonNode root) {
        var out = new HashMap<Long, Modernizations>();
        for (var e : root.path("modernizations").properties()) {
            long id = e.getValue().path("id").asLong();
            if (id == 0) continue;
            out.putIfAbsent(id, new Modernizations(e.getValue().path("name").asString(), e.getValue().path("icon").asString()));
        }
        return out;
    }

    private static Map<Long, ShipConfig> shipsTypeMap(JsonNode root) {
        var out = new HashMap<Long, ShipConfig>();
        for (var e : root.path("ships").properties()) {
            long shipId = Long.parseLong(e.getKey());
            var type = e.getValue().path("type").asString();
            out.putIfAbsent(shipId, new ShipConfig(type));
        }
        return out;
    }
}
