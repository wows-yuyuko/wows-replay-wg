package com.shinoaki.wowsreplay.dumper.web;

import com.shinoaki.wowsreplay.core.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * results_info 精简提取器（输出对标 wows-replays-web 的 {@code BattleData} 结构）。
 *
 * <p>输入为 {@code players[].vehicle.results_info} 的扁平大字典（见
 * {@code temp/compare/player-info.json}），输出只保留 web 前端实际消费的字段。
 * 白名单字段在原始数据中只要存在即保留——值为 {@code null} 时也原样输出，不丢弃。</p>
 *
 * <p>在 dumper 最终输出阶段（{@link com.shinoaki.wowsreplay.dumper.ReplayDumper#assemble}）执行。</p>
 */
public final class ResultsInfoExtractor {

    private ResultsInfoExtractor() {}

    /** 武器伤害字段（damage_*，来自 wows-replays-web damage-types.ts 的 DAMAGE_TYPE_DEFS）。 */
    public static final Set<String> DAMAGE_FIELDS = Set.of(
        "damage_main_ap", "damage_main_he", "damage_main_cs",
        "damage_atba_ap", "damage_atba_he", "damage_atba_cs",
        "damage_tpd_normal", "damage_tpd_deep", "damage_tpd_alter",
        "damage_rocket", "damage_skip", "damage_sea_mine",
        "damage_ram", "damage_missile",
        "damage_fire", "damage_flood",
        "damage_dbomb_direct", "damage_dbomb_splash",
        "damage_bomb", "damage_adbomb", "damage_tbomb"
    );

    /** 承受伤害字段（received_damage_*）。 */
    public static final Set<String> RECEIVED_DAMAGE_FIELDS = Set.of(
        "received_damage_main_ap", "received_damage_main_he", "received_damage_main_cs",
        "received_damage_atba_ap", "received_damage_atba_he", "received_damage_atba_cs",
        "received_damage_tpd_normal", "received_damage_tpd_alter", "received_damage_tpd_deep",
        "received_damage_rocket", "received_damage_skip", "received_damage_sea_mine",
        "received_damage_ram", "received_damage_missile",
        "received_damage_fire", "received_damage_flood",
        "received_damage_dbomb", "received_damage_bomb", "received_damage_adbomb",
        "received_damage_tbomb", "received_damage_special"
    );

    /** 命中字段（hits_*）。 */
    public static final Set<String> HITS_FIELDS = Set.of(
        "hits_main_ap", "hits_main_he", "hits_main_cs",
        "hits_atba_ap", "hits_atba_he", "hits_atba_cs",
        "hits_tpd",
        "hits_rocket", "hits_skip", "hits_sea_mine",
        "hits_ram", "hits_missile",
        "hits_fire", "hits_flood",
        "hits_dbomb", "hits_bomb", "hits_adbomb", "hits_tbomb"
    );

    /** 发射字段（shots_*）。 */
    private static final Set<String> SHOTS_FIELDS = Set.of(
        "shots_main_ap", "shots_main_he", "shots_main_cs",
        "shots_atba_ap", "shots_atba_he", "shots_atba_cs",
        "shots_tpd",
        "shots_rocket", "shots_skip", "shots_sea_mine",
        "shots_missile",
        "shots_dbomb", "shots_bomb", "shots_adbomb", "shots_tbomb"
    );

    /** 飞机相关字段（PersonalStats.vue / efficiency-config.ts 消费）。 */
    private static final Set<String> PLANES_STATS_FIELDS = Set.of(
        "planes_killed_by_ship", "planes_killed_by_sfighters",
        "planes_lost_sfighters", "planes_lost_fighters", "planes_lost_bombers",
        "planes_lost_tbombers", "planes_lost_skipbombers", "planes_lost"
    );

    /** 潜在伤害字段（agro_*，potential = 各值之和）。 */
    private static final Set<String> AGRO_FIELDS = Set.of(
        "agro_air", "agro_art", "agro_dbomb", "agro_tpd"
    );

    /** 点亮伤害字段（spotting）。 */
    private static final Set<String> SPOTTING_FIELDS = Set.of("scouting_damage");

    /** 逐目标交互只保留这些字段（含武器 damage_* / hits_* 白名单）。 */
    private static final Set<String> INTERACTION_FIELDS;
    static {
        var s = new LinkedHashSet<>(DAMAGE_FIELDS);
        s.addAll(HITS_FIELDS);
        s.add("scouting_damage");
        s.add("planes_killed");
        s.add("planes_killed_by_ship");
        s.add("ship_killed");
        INTERACTION_FIELDS = Set.copyOf(s);
    }

    /** 扁平 results_info → 精简 BattleData（字段存在即保留，含 null）。 */
    public static ObjectNode extract(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return JsonMapper.createObject();
        }

        ObjectNode out = JsonMapper.createObject();

        copyScalar(out, raw, "damage", "damage");
        copyScalar(out, raw, "exp", "exp");
        copyScalar(out, raw, "max_health", "maxHealth");
        copyScalar(out, raw, "is_alive", "alive");
        copyScalar(out, raw, "ships_killed", "shipsKilled");

        out.set("spotting", extractByNames(raw, SPOTTING_FIELDS));
        out.set("agro", extractByNames(raw, AGRO_FIELDS));
        out.set("hits", extractByNames(raw, HITS_FIELDS));
        out.set("shots", extractByNames(raw, SHOTS_FIELDS));
        out.set("ribbons", extractByPrefix(raw, "RIBBON_"));
        out.set("planesStats", extractByNames(raw, PLANES_STATS_FIELDS));
        out.set("damageDealt", extractByNames(raw, DAMAGE_FIELDS));
        out.set("damageReceived", extractByNames(raw, RECEIVED_DAMAGE_FIELDS));

        JsonNode interactions = raw.get("interactions");
        if (interactions != null && interactions.isObject()) {
            ObjectNode interOut = JsonMapper.createObject();
            for (var prop : interactions.properties()) {
                JsonNode v = prop.getValue();
                interOut.set(prop.getKey(),
                    v.isObject() ? extractByNames(v, INTERACTION_FIELDS) : v);
            }
            out.set("interactions", interOut);
        } else {
            out.set("interactions", JsonNodeFactory.instance.objectNode());
        }
        return out;
    }

    /** 字段存在即拷贝到目标键（值为 null 也保留）。 */
    private static void copyScalar(ObjectNode out, JsonNode raw, String srcName, String dstName) {
        if (raw.has(srcName)) {
            out.set(dstName, raw.get(srcName));
        }
    }

    /** 按白名单抽取（字段存在即保留，含 null）。 */
    private static ObjectNode extractByNames(JsonNode raw, Set<String> names) {
        ObjectNode obj = JsonMapper.createObject();
        for (String name : names) {
            if (raw.has(name)) {
                obj.set(name, raw.get(name));
            }
        }
        return obj;
    }

    /** 按前缀抽取（字段存在即保留，含 null）。 */
    private static ObjectNode extractByPrefix(JsonNode raw, String prefix) {
        ObjectNode obj = JsonMapper.createObject();
        for (var prop : raw.properties()) {
            if (prop.getKey().startsWith(prefix)) {
                obj.set(prop.getKey(), prop.getValue());
            }
        }
        return obj;
    }
}
