package com.shinoaki.wowsreplay.dumper.web;

import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import com.shinoaki.wowsreplay.dumper.web.data.BattleTimeline;
import com.shinoaki.wowsreplay.dumper.web.data.TeamGap;
import com.shinoaki.wowsreplay.dumper.web.data.TeamTimeline;
import com.shinoaki.wowsreplay.dumper.web.data.TimelinePoint;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 战斗时间线计算器（对标 wows-replays-web {@code DamageTimeline.vue}）：
 * 个人/团队累计伤害、团队分数、血池剩余量，以及团队差距（血池%差 + 分数差）。
 *
 * <p>依赖小地图<b>流式处理</b>产物：{@code damage_events}（伤害事件）、{@code shot_hits}
 * （解析鱼雷/飞机等非舰船 aggressor）、{@code frames}（逐帧实体血量 + 队伍分数）、
 * {@code dead_ships}（死亡时刻）。玩家身份统一用 {@code meta_id}。</p>
 *
 * <p>在 dumper 最终输出阶段（{@code ReplayDumper#assemble}）执行。</p>
 */
public final class BattleTimelineCalculator {

    private BattleTimelineCalculator() {}

    /** 玩家元信息（metaId 全局一致）。 */
    private record PlayerMeta(long metaId, long accountId, int teamId, double battleDamage, double maxHealth) {}

    /** 逐帧血量快照（双队配对，用于差距尾部追补）。 */
    private record HealthSnap(double clock, double teamAHp, double teamBHp) {}

    /**
     * 计算时间线。
     *
     * @param players       players 数组节点（dumper 输出），元素需含 {@code meta_id} / {@code account_id} /
     *                      {@code team_id}
     * @param battleResults 已精简的 battle_results（{@code playersPublicInfo[account_id]} 已是 BattleData）
     * @param minimap       小地图流式处理产物（damage_events / shot_hits / frames / dead_ships）
     * @param duration      正赛时长（秒）
     */
    public static BattleTimeline calculate(JsonNode players, JsonNode battleResults,
                                           MinimapOutput minimap, double duration) {
        // 1. metaId → PlayerMeta
        Map<Long, PlayerMeta> byMeta = new LinkedHashMap<>();
        Map<Long, PlayerMeta> byAccount = new LinkedHashMap<>();
        if (players != null && players.isArray()) {
            for (JsonNode p : players) {
                JsonNode metaNode = p.get("meta_id");
                if (metaNode == null || !metaNode.isNumber()) continue;
                long metaId = metaNode.longValue();
                long accountId = p.path("account_id").asLong(-1);
                int teamId = p.path("team_id").asInt(-1);
                JsonNode data = resultsInfoOf(battleResults, String.valueOf(accountId));
                double damage = data != null ? data.path("damage").asDouble(0) : 0;
                double maxHp = data != null ? data.path("maxHealth").asDouble(0) : 0;
                PlayerMeta pm = new PlayerMeta(metaId, accountId, teamId, damage, maxHp);
                byMeta.put(metaId, pm);
                byAccount.put(accountId, pm);
            }
        }

        var damageEvents = minimap != null ? minimap.damageEvents() : List.<MinimapOutput.DamageEntry>of();
        var shotHits = minimap != null ? minimap.shotHits() : List.<MinimapOutput.ShotHitEntry>of();
        var frames = minimap != null ? minimap.frames() : List.<MinimapOutput.MinimapFrame>of();
        var deadShips = minimap != null ? minimap.deadShips() : List.<MinimapOutput.DeadShip>of();

        // 2. shot_hits：victim + floor(clock) → owner（解析鱼雷/飞机 aggressor）
        Map<String, Long> shotOwnerMap = new HashMap<>();
        for (var hit : shotHits) {
            shotOwnerMap.put(hit.victimMetaId() + "_" + (long) Math.floor(hit.clock()), hit.ownerMetaId());
        }
        Function<MinimapOutput.DamageEntry, PlayerMeta> resolve = ev -> {
            PlayerMeta pm = byMeta.get(ev.aggressorMetaId());
            if (pm != null) return pm;
            Long owner = shotOwnerMap.get(ev.victimMetaId() + "_" + (long) Math.floor(ev.clock()));
            return owner != null ? byMeta.get(owner) : null;
        };

        double firstClock = damageEvents.isEmpty() ? 0 : damageEvents.get(0).clock();
        double lastClock = damageEvents.isEmpty() ? duration : damageEvents.get(damageEvents.size() - 1).clock();
        int minSec = (int) Math.floor(firstClock);
        int maxSec = (int) Math.max(Math.ceil(lastClock), Math.ceil(duration) + 10);
        int totalRange = maxSec - minSec;

        // 3. 个人累计伤害（按秒分桶 + battleData.damage 校准）
        Map<Long, Map<Integer, Double>> playerBucket = new HashMap<>();
        for (var ev : damageEvents) {
            PlayerMeta pm = resolve.apply(ev);
            if (pm == null) continue;
            int sec = (int) Math.floor(ev.clock());
            playerBucket.computeIfAbsent(pm.accountId(), k -> new HashMap<>())
                .merge(sec, (double) ev.amount(), Double::sum);
        }
        Map<String, List<TimelinePoint>> playersTimeline = new LinkedHashMap<>();
        for (var e : playerBucket.entrySet()) {
            List<TimelinePoint> points = cumulativePoints(e.getValue(), minSec, maxSec);
            PlayerMeta pm = byAccount.get(e.getKey());
            double battleDmg = pm != null ? pm.battleDamage() : 0;
            double eventsFinal = points.isEmpty() ? 0 : points.get(points.size() - 1).value();
            if (battleDmg > 0 && eventsFinal > 0 && Math.round(eventsFinal) != Math.round(battleDmg)) {
                double ratio = battleDmg / eventsFinal;
                List<TimelinePoint> calibrated = new ArrayList<>();
                for (var pt : points) {
                    calibrated.add(new TimelinePoint(pt.clock(), Math.round(pt.value() * ratio)));
                }
                points = calibrated;
            }
            playersTimeline.put(String.valueOf(e.getKey()), points);
        }

        // 4. 团队累计伤害 + 分数（自适应分桶：前50%按30s，中30%按10s，后20%按秒）
        Function<Double, Long> bucketKey = t -> {
            double ratio = totalRange > 0 ? (t - minSec) / (double) totalRange : 1;
            if (ratio < 0.5) return (long) (minSec + Math.floor((t - minSec) / 30) * 30);
            if (ratio < 0.8) return (long) (minSec + Math.floor((t - minSec) / 10) * 10);
            return (long) Math.floor(t);
        };
        TreeSet<Long> allKeys = new TreeSet<>();
        for (long t = minSec; t <= maxSec; t++) {
            allKeys.add(bucketKey.apply((double) t));
        }

        Map<Integer, Map<Long, Double>> teamDmg = new HashMap<>();
        for (var ev : damageEvents) {
            PlayerMeta pm = resolve.apply(ev);
            if (pm == null) continue;
            long key = bucketKey.apply((double) Math.floor(ev.clock()));
            teamDmg.computeIfAbsent(pm.teamId(), k -> new HashMap<>()).merge(key, (double) ev.amount(), Double::sum);
        }
        Map<Integer, Map<Long, Double>> teamScore = new HashMap<>();
        for (var f : frames) {
            long key = bucketKey.apply((double) Math.floor(f.clock()));
            for (var ts : f.teamScores()) {
                teamScore.computeIfAbsent(ts.teamIndex(), k -> new HashMap<>()).put(key, (double) ts.score());
            }
        }

        // 5. 团队血池：初始总量 + 逐帧配对快照
        Map<Long, Double> entityMaxHealth = new HashMap<>();
        Map<Long, Integer> entityTeam = new HashMap<>();
        for (var f : frames) {
            for (var e : f.entities()) {
                if (e.maxHealth() > 0 && !entityMaxHealth.containsKey(e.metaId())) {
                    entityMaxHealth.put(e.metaId(), (double) e.maxHealth());
                    entityTeam.put(e.metaId(), e.teamId());
                }
            }
        }
        for (var pm : byMeta.values()) {
            if (pm.maxHealth() > 0 && !entityMaxHealth.containsKey(pm.metaId())) {
                entityMaxHealth.put(pm.metaId(), pm.maxHealth());
                entityTeam.put(pm.metaId(), pm.teamId());
            }
        }
        Map<Long, Double> deathClock = new HashMap<>();
        for (var ds : deadShips) {
            deathClock.put(ds.victimMetaId(), (double) ds.clock());
        }
        Map<Integer, Double> initialHp = new HashMap<>();
        for (var e : entityTeam.entrySet()) {
            initialHp.merge(e.getValue(), entityMaxHealth.getOrDefault(e.getKey(), 0.0), Double::sum);
        }

        int teamA = initialHp.isEmpty() ? 0 : Collections.min(initialHp.keySet());
        int teamB = initialHp.size() > 1
            ? initialHp.keySet().stream().filter(t -> t != teamA).findFirst().orElse(-1)
            : -1;

        List<HealthSnap> snapshots = new ArrayList<>();
        Map<Integer, List<TimelinePoint>> teamHealth = new HashMap<>();
        Map<Long, Double> lastKnownHealth = new HashMap<>();
        for (var f : frames) {
            double clock = f.clock();
            for (var e : f.entities()) {
                if (e.health() > 0 || !e.isAlive()) {
                    lastKnownHealth.put(e.metaId(), (double) e.health());
                }
            }
            double hpA = 0, hpB = 0;
            for (var e : entityTeam.entrySet()) {
                long metaId = e.getKey();
                int team = e.getValue();
                if (isDeadAt(metaId, clock, deathClock)) continue;
                double h = lastKnownHealth.getOrDefault(metaId, entityMaxHealth.getOrDefault(metaId, 0.0));
                if (team == teamA) hpA += h;
                else if (team == teamB) hpB += h;
            }
            snapshots.add(new HealthSnap(clock, hpA, hpB));
            teamHealth.computeIfAbsent(teamA, k -> new ArrayList<>()).add(new TimelinePoint(clock, hpA));
            if (teamB >= 0) {
                teamHealth.computeIfAbsent(teamB, k -> new ArrayList<>()).add(new TimelinePoint(clock, hpB));
            }
        }

        // 6. 装配每队时间线（对齐统一 bucket 时间轴）
        TreeSet<Integer> teamIds = new TreeSet<>(teamDmg.keySet());
        teamIds.addAll(teamScore.keySet());
        teamIds.addAll(initialHp.keySet());
        Map<String, TeamTimeline> teamsTimeline = new LinkedHashMap<>();
        for (int teamId : teamIds) {
            var dmgBucket = teamDmg.getOrDefault(teamId, Map.of());
            var scoreBucket = teamScore.getOrDefault(teamId, Map.of());
            List<TimelinePoint> dmgPoints = new ArrayList<>();
            List<TimelinePoint> scorePoints = new ArrayList<>();
            double cum = 0, lastScore = 0;
            for (long key : allKeys) {
                cum += dmgBucket.getOrDefault(key, 0.0);
                dmgPoints.add(new TimelinePoint(key, cum));
                lastScore = scoreBucket.getOrDefault(key, lastScore);
                scorePoints.add(new TimelinePoint(key, lastScore));
            }
            teamsTimeline.put(String.valueOf(teamId), new TeamTimeline(
                initialHp.getOrDefault(teamId, 0.0),
                dmgPoints,
                scorePoints,
                teamHealth.getOrDefault(teamId, List.of())));
        }

        // 7. 团队差距：health% 差 + 分数差（team0 − team1）
        TeamGap gap = buildGap(allKeys, teamA, teamB, initialHp, snapshots, teamScore);

        return new BattleTimeline(duration, minSec, maxSec, playersTimeline, teamsTimeline, gap);
    }

    private static TeamGap buildGap(TreeSet<Long> allKeys, int teamA, int teamB,
                                    Map<Integer, Double> initialHp,
                                    List<HealthSnap> snapshots,
                                    Map<Integer, Map<Long, Double>> teamScore) {
        List<TimelinePoint> healthGap = new ArrayList<>();
        List<TimelinePoint> scoreGap = new ArrayList<>();
        healthGap.add(new TimelinePoint(0, 0));
        scoreGap.add(new TimelinePoint(0, 0));

        double initA = initialHp.getOrDefault(teamA, 0.0);
        double initB = initialHp.getOrDefault(teamB, 0.0);
        var scoreBucketA = teamScore.getOrDefault(teamA, Map.of());
        var scoreBucketB = teamScore.getOrDefault(teamB, Map.of());

        int si = 0;
        double lastScoreA = 0, lastScoreB = 0;
        for (long key : allKeys) {
            if (key <= 0) continue;
            while (si < snapshots.size() && snapshots.get(si).clock() <= key) si++;
            HealthSnap snap = si > 0 ? snapshots.get(si - 1) : null;
            double pctA = snap != null && initA > 0 ? snap.teamAHp() / initA * 100 : 100;
            double pctB = snap != null && initB > 0 ? snap.teamBHp() / initB * 100 : 100;
            healthGap.add(new TimelinePoint(key, Math.round((pctA - pctB) * 10) / 10.0));
            lastScoreA = scoreBucketA.getOrDefault(key, lastScoreA);
            lastScoreB = scoreBucketB.getOrDefault(key, lastScoreB);
            scoreGap.add(new TimelinePoint(key, lastScoreA - lastScoreB));
        }

        // 尾部追补：最后帧血量超过最后 score 点（DOT 持续等）
        long lastKey = allKeys.isEmpty() ? 0 : allKeys.last();
        for (int j = si; j < snapshots.size(); j++) {
            HealthSnap snap = snapshots.get(j);
            if (snap.clock() <= lastKey) continue;
            double pctA = initA > 0 ? snap.teamAHp() / initA * 100 : 0;
            double pctB = initB > 0 ? snap.teamBHp() / initB * 100 : 0;
            healthGap.add(new TimelinePoint(snap.clock(), Math.round((pctA - pctB) * 10) / 10.0));
        }

        return new TeamGap(healthGap, scoreGap);
    }

    /** 逐秒累计：从 minSec 到 maxSec，每秒推累计值。 */
    private static List<TimelinePoint> cumulativePoints(Map<Integer, Double> secMap, int minSec, int maxSec) {
        List<Integer> sortedSecs = new ArrayList<>(secMap.keySet());
        Collections.sort(sortedSecs);
        List<TimelinePoint> points = new ArrayList<>();
        double cum = 0;
        int si = 0;
        for (int t = minSec; t <= maxSec; t++) {
            if (si < sortedSecs.size() && sortedSecs.get(si) == t) {
                cum += secMap.getOrDefault(t, 0.0);
                si++;
            }
            points.add(new TimelinePoint(t, cum));
        }
        return points;
    }

    private static boolean isDeadAt(long metaId, double clock, Map<Long, Double> deathClock) {
        Double dc = deathClock.get(metaId);
        return dc != null && clock >= dc;
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
