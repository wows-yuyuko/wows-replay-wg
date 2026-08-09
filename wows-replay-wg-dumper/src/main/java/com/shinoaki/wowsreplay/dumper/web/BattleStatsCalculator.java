package com.shinoaki.wowsreplay.dumper.web;

import com.shinoaki.wowsreplay.dumper.web.data.AttackDetail;
import com.shinoaki.wowsreplay.dumper.web.data.BattleStats;
import com.shinoaki.wowsreplay.dumper.web.data.PlayerStats;
import com.shinoaki.wowsreplay.dumper.web.data.ReceivedDetail;
import com.shinoaki.wowsreplay.dumper.web.data.SpotDetail;
import com.shinoaki.wowsreplay.dumper.web.data.TeamStats;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 战斗统计计算器：基于精简后的 {@code players[].vehicle.results_info}（BattleData 结构），
 * 汇总团队与个人统计，输出 {@link BattleStats}（团队 {@link TeamStats} + 个人 {@link PlayerStats}）。
 *
 * <p>{@code attack / received_from} 的明细精确到武器伤害（damage_*）与命中数（hits_*），
 * 与 wows-replays-web 的 {@code totalDamage} 口径一致（仅统计武器白名单）。</p>
 *
 * <p>在 dumper 最终输出阶段（{@code ReplayDumper#assemble}）执行。</p>
 */
public final class BattleStatsCalculator {

    private BattleStatsCalculator() {}

    /** 单玩家统计容器。 */
    private record PlayerEntry(String id, int team, JsonNode data) {}

    /**
     * 计算团队与个人统计。
     *
     * @param players      players 数组节点（dumper 输出），元素需含 {@code account_id}、{@code team_id}
     * @param battleResults 已精简的 battle_results（{@code playersPublicInfo[account_id]} 已是 BattleData）
     * @return 团队 + 个人统计
     */
    public static BattleStats calculate(JsonNode players, JsonNode battleResults) {
        Map<String, PlayerEntry> byId = new LinkedHashMap<>();
        if (players != null && players.isArray()) {
            for (JsonNode p : players) {
                JsonNode idNode = p.get("account_id");
                if (idNode == null) continue;
                JsonNode data = resultsInfoOf(battleResults, idNode.asString());
                if (data == null) continue;
                int team = p.path("team_id").asInt(-1);
                byId.put(idNode.asString(), new PlayerEntry(idNode.asString(), team, data));
            }
        }

        // 团队汇总：damage / hp_pool / potential / scouting
        Map<String, double[]> teamAcc = new LinkedHashMap<>();
        for (PlayerEntry e : byId.values()) {
            double[] acc = teamAcc.computeIfAbsent(String.valueOf(e.team), k -> new double[4]);
            acc[0] += number(e.data.get("damage"));
            acc[1] += number(e.data.get("maxHealth"));
            acc[2] += sumNumbers(e.data.get("agro"));
            acc[3] += spotting(e.data);
        }
        Map<String, TeamStats> teams = new LinkedHashMap<>();
        for (var entry : teamAcc.entrySet()) {
            double[] a = entry.getValue();
            teams.put(entry.getKey(), new TeamStats(a[0], a[1], a[2], a[3]));
        }

        // 个人明细容器：attack / received_from / spotted
        Map<String, Map<String, AttackDetail>> attacks = new LinkedHashMap<>();
        Map<String, Map<String, ReceivedDetail>> received = new LinkedHashMap<>();
        Map<String, Map<String, SpotDetail>> spotted = new LinkedHashMap<>();
        for (PlayerEntry e : byId.values()) {
            attacks.put(e.id, new LinkedHashMap<>());
            received.put(e.id, new LinkedHashMap<>());
            spotted.put(e.id, new LinkedHashMap<>());
        }

        // 逐目标交互：attack / received_from / spotted
        for (PlayerEntry attacker : byId.values()) {
            JsonNode inter = attacker.data.get("interactions");
            if (inter == null || !inter.isObject()) continue;
            for (var prop : inter.properties()) {
                String targetId = prop.getKey();
                JsonNode v = prop.getValue();
                if (!v.isObject()) continue;

                AttackDetail detail = attackDetail(v);
                attacks.get(attacker.id).put(targetId, detail);

                // 点亮了谁（scouting_damage > 0）
                double spot = number(v.get("scouting_damage"));
                if (spot > 0) {
                    spotted.get(attacker.id).put(targetId, new SpotDetail(spot));
                }

                // 被谁打了（反向登记到受害者）
                PlayerEntry victim = byId.get(targetId);
                if (victim != null) {
                    received.get(victim.id).put(attacker.id,
                        new ReceivedDetail(detail.total(), detail.damage(), detail.hits()));
                }
            }
        }

        // 个人标量 + 汇总
        Map<String, PlayerStats> playersStats = new LinkedHashMap<>();
        for (PlayerEntry e : byId.values()) {
            playersStats.put(e.id, new PlayerStats(
                e.team,
                number(e.data.get("damage")),
                sumNumbers(e.data.get("damageReceived")),
                spotting(e.data),
                sumNumbers(e.data.get("agro")),
                number(e.data.get("shipsKilled")),
                number(e.data.get("maxHealth")),
                attacks.get(e.id),
                received.get(e.id),
                spotted.get(e.id)
            ));
        }

        return new BattleStats(teams, playersStats);
    }

    /** 单条交互 → {@link AttackDetail}（total 只统计武器白名单）。 */
    private static AttackDetail attackDetail(JsonNode v) {
        return new AttackDetail(
            sumNumbers(v, ResultsInfoExtractor.DAMAGE_FIELDS),
            pickNumbers(v, ResultsInfoExtractor.DAMAGE_FIELDS),
            pickNumbers(v, ResultsInfoExtractor.HITS_FIELDS),
            toNumber(v.get("scouting_damage")),
            toNumber(v.get("planes_killed")),
            toNumber(v.get("planes_killed_by_ship")),
            toNumber(v.get("ship_killed"))
        );
    }

    /** 按白名单抽取数值（字段存在即保留，含 null）。 */
    private static Map<String, Number> pickNumbers(JsonNode raw, Set<String> names) {
        Map<String, Number> m = new LinkedHashMap<>();
        for (String name : names) {
            if (raw.has(name)) {
                m.put(name, toNumber(raw.get(name)));
            }
        }
        return m;
    }

    private static Number toNumber(JsonNode node) {
        return node != null && node.isNumber() ? node.numberValue() : null;
    }

    /** 对象内所有数值求和（null/缺失按 0）。 */
    private static double sumNumbers(JsonNode obj) {
        if (obj == null || !obj.isObject()) return 0;
        double s = 0;
        for (var v : obj) {
            s += number(v);
        }
        return s;
    }

    private static double sumNumbers(JsonNode obj, Set<String> names) {
        if (obj == null || !obj.isObject()) return 0;
        double s = 0;
        for (String name : names) {
            s += number(obj.get(name));
        }
        return s;
    }

    private static double number(JsonNode node) {
        return node != null && node.isNumber() ? node.doubleValue() : 0;
    }

    private static double spotting(JsonNode data) {
        JsonNode spotting = data.get("spotting");
        return spotting != null ? number(spotting.get("scouting_damage")) : 0;
    }

    /** {@code battle_results.playersPublicInfo[account_id]}（已精简为 BattleData），缺失返回 null。 */
    private static JsonNode resultsInfoOf(JsonNode battleResults, String accountId) {
        if (battleResults == null) return null;
        JsonNode publicInfo = battleResults.get("playersPublicInfo");
        if (publicInfo == null) return null;
        JsonNode ri = publicInfo.get(accountId);
        return ri != null && ri.isObject() ? ri : null;
    }
}
