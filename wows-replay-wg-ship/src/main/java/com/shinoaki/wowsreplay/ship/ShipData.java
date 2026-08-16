package com.shinoaki.wowsreplay.ship;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.data.WowsInfo;
import com.shinoaki.wowsreplay.ship.model.AbilityInfo;
import com.shinoaki.wowsreplay.ship.model.ModuleOption;
import com.shinoaki.wowsreplay.ship.model.ModuleSlot;
import com.shinoaki.wowsreplay.ship.model.ShipInfo;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * wowsinfo.json 的完整解析结果。
 *
 * <ul>
 *   <li>{@code ships} —— 完整战舰配置（含 modules/components 组件树）；</li>
 *   <li>{@code wowsInfo} —— 复用 core 的 id 映射（modernizations/exteriors/skills/按 id 的消耗品/舰种）；</li>
 *   <li>{@code abilitiesByName} —— 按名称索引的消耗品定义（用于 ship.consumables[][].name 本地化）；</li>
 *   <li>{@code aliases} —— 联名船等的中文别名；{@code version} —— 数据版本。</li>
 * </ul>
 */
public record ShipData(
        Map<Long, ShipInfo> ships,
        WowsInfo wowsInfo,
        Map<String, AbilityInfo> abilitiesByName,
        Map<Long, String> aliases,
        String version
) {

    /** 解析 wowsinfo.json 文本。 */
    public static ShipData fromJson(String json) {
        JsonNode root = JsonMapper.readTree(json);
        return new ShipData(
                parseShips(root.path("ships")),
                WowsInfo.fromJson(json),
                parseAbilities(root.path("abilities")),
                parseAliases(root.path("alias")),
                root.path("version").asString(""));
    }

    public ShipInfo ship(long id) {
        return ships.get(id);
    }

    /** 按舰船索引（如 "PASD001"）查找。 */
    public Optional<ShipInfo> shipByIndex(String index) {
        if (index == null) return Optional.empty();
        String wanted = index.trim();
        return ships.values().stream()
                .filter(s -> s.index() != null && s.index().equalsIgnoreCase(wanted))
                .findFirst();
    }

    public AbilityInfo abilityByName(String name) {
        return name == null ? null : abilitiesByName.get(name);
    }

    // ── 解析：ships ──────────────────────────────────────────────

    private static Map<Long, ShipInfo> parseShips(JsonNode node) {
        var out = new LinkedHashMap<Long, ShipInfo>();
        if (node == null || !node.isObject()) return out;
        for (var e : node.properties()) {
            long id;
            try {
                id = Long.parseLong(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            out.put(id, parseShip(id, e.getValue()));
        }
        return out;
    }

    private static ShipInfo parseShip(long id, JsonNode s) {
        return new ShipInfo(
                id,
                s.path("name").asString(""),
                s.path("description").asString(""),
                s.path("year").asString(""),
                s.path("paperShip").asBoolean(false),
                s.path("index").asString(""),
                s.path("tier").asInt(0),
                s.path("region").asString(""),
                s.path("type").asString(""),
                s.path("regionID").asString(""),
                s.path("typeID").asString(""),
                s.path("group").asString(""),
                s.path("costXP").asLong(0L),
                s.path("costGold").asLong(0L),
                s.path("costCR").asLong(0L),
                parseConsumables(s.path("consumables")),
                longList(s.path("nextShips")),
                stringList(s.path("permoflages")),
                parseModules(s.path("modules")),
                s.get("components"));
    }

    private static List<List<ShipInfo.ConsumableSlot>> parseConsumables(JsonNode node) {
        var out = new ArrayList<List<ShipInfo.ConsumableSlot>>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode slot : node) {
            var variants = new ArrayList<ShipInfo.ConsumableSlot>();
            if (slot.isArray()) {
                for (JsonNode c : slot) {
                    variants.add(new ShipInfo.ConsumableSlot(
                            c.path("name").asString(""), c.path("type").asString("")));
                }
            }
            out.add(variants);
        }
        return out;
    }

    private static List<ModuleSlot> parseModules(JsonNode node) {
        var byKey = new LinkedHashMap<String, ModuleSlot>();
        if (node != null && node.isObject()) {
            for (var e : node.properties()) {
                String key = e.getKey();
                byKey.put(key, new ModuleSlot(key, SlotNames.label(key), parseOptions(e.getValue())));
            }
        }
        var out = new ArrayList<ModuleSlot>();
        for (String key : SlotNames.SLOT_ORDER) {
            ModuleSlot slot = byKey.get(key);
            if (slot != null) out.add(slot);
        }
        for (var e : byKey.entrySet()) {
            if (!SlotNames.SLOT_ORDER.contains(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    private static List<ModuleOption> parseOptions(JsonNode node) {
        var out = new ArrayList<ModuleOption>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode option : node) {
            JsonNode cost = option.path("cost");
            var components = new LinkedHashMap<String, List<String>>();
            JsonNode comps = option.get("components");
            if (comps != null && comps.isObject()) {
                for (var e : comps.properties()) {
                    components.put(e.getKey(), stringList(e.getValue()));
                }
            }
            out.add(new ModuleOption(
                    option.path("index").asInt(0),
                    option.path("name").asString(""),
                    cost.path("costXP").asLong(0L),
                    cost.path("costCR").asLong(0L),
                    components));
        }
        return out;
    }

    // ── 解析：abilities / alias ───────────────────────────────────

    private static Map<String, AbilityInfo> parseAbilities(JsonNode node) {
        var out = new LinkedHashMap<String, AbilityInfo>();
        if (node == null || !node.isObject()) return out;
        for (var e : node.properties()) {
            JsonNode a = e.getValue();
            out.put(e.getKey(), new AbilityInfo(
                    e.getKey(),
                    a.path("id").asLong(0L),
                    a.path("nation").asString(""),
                    a.path("name").asString(""),
                    a.path("description").asString(""),
                    a.path("icon").asString(""),
                    a.path("filter").asString(""),
                    a.path("type").asString(""),
                    a.get("abilities"),
                    a.get("alter")));
        }
        return out;
    }

    private static Map<Long, String> parseAliases(JsonNode node) {
        var out = new LinkedHashMap<Long, String>();
        if (node == null || !node.isObject()) return out;
        for (var e : node.properties()) {
            try {
                out.put(Long.parseLong(e.getKey()), e.getValue().path("alias").asString(""));
            } catch (NumberFormatException ignored) {
                // 忽略无法解析的键
            }
        }
        return out;
    }

    // ── 通用小工具 ──────────────────────────────────────────────

    private static List<Long> longList(JsonNode node) {
        var out = new ArrayList<Long>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode v : node) {
            if (v.isNumber()) out.add(v.asLong());
        }
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        var out = new ArrayList<String>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode v : node) {
            if (v.isTextual()) out.add(v.asString());
        }
        return out;
    }
}
