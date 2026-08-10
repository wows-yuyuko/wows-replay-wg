package com.shinoaki.wowsreplay.dumper.web;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.dumper.ReplayDumper;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.DeadShip;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.DamageEntry;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.MinimapEntity;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.MinimapFrame;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.ShotHitEntry;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput.TeamScoreEntry;
import com.shinoaki.wowsreplay.dumper.web.data.BattleStats;
import com.shinoaki.wowsreplay.dumper.web.data.BattleTimeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * dumper web 输出测试：最终输出阶段精简 results_info + battle_stats + battle_timeline。
 */
class WebFunctionTest {

    private static final ObjectNode NF = JsonMapper.createObject();

    // ── 计算器单元测试（合成数据，精确断言）──────────────────────────────

    @Test
    @DisplayName("BattleStatsCalculator + BattleTimelineCalculator 精确输出")
    void calculatorsOutput() {
        ArrayNode players = JsonMapper.getMapper().getNodeFactory().arrayNode();
        players.add(playerNode(100, 100, 0));
        players.add(playerNode(200, 200, 1));

        ObjectNode resolvedResults = NF.deepCopy();
        ObjectNode publicInfo = NF.deepCopy();
        publicInfo.set("100", rawResults(1000, 100, 50000, true, 1,
            2000, 500, 300, 400, "200", interactionA()));
        publicInfo.set("200", rawResults(2000, 200, 40000, false, 0,
            1000, 0, 100, 500, "100", interactionB()));
        resolvedResults.set("playersPublicInfo", publicInfo);

        BattleStats stats = BattleStatsCalculator.calculate(players, resolvedResults);
        JsonNode st = JsonMapper.toTree(stats);
        assertEquals(1000, st.path("teams").path("0").path("damage").asDouble(), "0队总伤害");
        assertEquals(50000, st.path("teams").path("0").path("hp_pool").asDouble(), "0队总血池");
        assertEquals(2500, st.path("teams").path("0").path("potential").asDouble(), "0队总潜在");
        assertEquals(300, st.path("teams").path("0").path("scouting").asDouble(), "0队总点亮");
        JsonNode pA = st.path("players").path("100");
        assertEquals(400, pA.path("received").asDouble(), "A 承受伤害");
        assertEquals(33372, pA.path("attack").path("200").path("total").asDouble(), "A 打200总伤害");
        assertEquals(32872, pA.path("attack").path("200").path("damage").path("damage_main_he").asDouble());
        assertEquals(10, pA.path("attack").path("200").path("hits").path("hits_main_he").asDouble());
        assertEquals(12345, pA.path("received_from").path("200").path("total").asDouble(), "A 被200打");
        assertEquals(26502, pA.path("spotted").path("200").path("scouting_damage").asDouble(), "A 点亮200");

        BattleTimeline timeline = BattleTimelineCalculator.calculate(players, resolvedResults, minimap(), 50);
        JsonNode tl = JsonMapper.toTree(timeline);
        JsonNode tlA = tl.path("players").path("100");
        assertEquals(1000, tlA.get(tlA.size() - 1).path("value").asDouble(), "A 累计伤害不校准");
        JsonNode tlB = tl.path("players").path("200");
        assertEquals(2000, tlB.get(tlB.size() - 1).path("value").asDouble(), "B 累计 400→校准 2000");
        JsonNode scoreGap = tl.path("gap").path("score_gap");
        assertEquals(40, scoreGap.get(scoreGap.size() - 1).path("value").asDouble(), "分数差 120-80=40");
    }

    @Test
    @DisplayName("results_info 白名单字段为 null 时保留")
    void nullFieldsKept() {
        ObjectNode raw = NF.deepCopy();
        raw.put("damage", 500);
        raw.put("exp", 50);
        raw.put("damage_main_he", 400);
        raw.set("hits_atba_cs", JsonMapper.getMapper().getNodeFactory().nullNode());
        raw.set("shots_tpd", JsonMapper.getMapper().getNodeFactory().nullNode());

        JsonNode compact = ResultsInfoExtractor.extract(raw);

        assertTrue(compact.path("hits").has("hits_atba_cs"), "null 命中字段保留");
        assertTrue(compact.path("hits").get("hits_atba_cs").isNull(), "保留为 null");
        assertTrue(compact.path("shots").has("shots_tpd"), "null 发射字段保留");
        assertEquals(400, compact.path("damageDealt").path("damage_main_he").asDouble());
    }

    @Test
    @DisplayName("真实 temp/compare/player-info.json 提取")
    void extractRealPlayerInfo() throws Exception {
        Path p = Path.of("../temp/compare/player-info.json");
        assumeTrue(Files.exists(p), "缺少 temp/compare/player-info.json");
        JsonNode raw = JsonMapper.readTree(Files.readAllBytes(p));
        JsonNode compact = ResultsInfoExtractor.extract(raw);

        assertEquals(raw.path("damage").asDouble(), compact.path("damage").asDouble());
        assertEquals(raw.path("max_health").asDouble(), compact.path("maxHealth").asDouble());
        assertEquals(raw.path("is_alive").asBoolean(), compact.path("alive").asBoolean());
        assertEquals(raw.path("scouting_damage").asDouble(),
            compact.path("spotting").path("scouting_damage").asDouble(), "spotting.scouting_damage");
        assertTrue(compact.path("ribbons").has("RIBBON_MAIN_CALIBER"), "ribbons 全量保留");
        assertFalse(compact.has("victory_points_own_ship_kill"), "非白名单丢弃");
    }

    // ── 端到端：ReplayDumper 最终输出（真实回放）────────────────────────

    @Test
    @DisplayName("从 temp 加载真实回放：dumper 最终输出含 battle_stats + battle_timeline")
    void realReplayOutput() throws Exception {
        Path repDir = Path.of("../temp/wg_15.6");
        assumeTrue(Files.isDirectory(repDir), "缺少 temp/wg_15.6 回放目录");
        Path rep = firstReplay(repDir);
        assumeTrue(rep != null, "temp/wg_15.6 下没有 .wowsreplay 文件");

        var replay = ReplayFile.fromFile(rep, Path.of("../temp/wows-data"));
        assumeTrue(GameDataCache.resolveGameDataDir(replay) != null, "缺少匹配版本的游戏数据");

        var options = new ReplayDumper.Options(true, 7, false, null);
        String json = new ReplayDumper(replay, options).dumpJson();
        JsonNode out = JsonMapper.readTree(json);

        // 0. master_meta_id = 主视角(self)玩家 meta_id
        assertTrue(out.path("master_meta_id").isNumber() && out.path("master_meta_id").asLong() > 0,
            "master_meta_id 应为主视角玩家 meta_id");

        // 1. players 不再内嵌 results_info（webFunction 计算数据从 battle_results 取数）
        JsonNode players = out.path("players");
        assertTrue(players.isArray() && players.size() >= 2, "真实回放应有多个玩家");
        for (JsonNode p : players) {
            assertTrue(p.path("account_id").isNumber(), "players 含 account_id");
            assertFalse(p.path("vehicle").has("results_info"), "vehicle 不再内嵌 results_info");
        }

        // 2. battle_results 原样输出：playersPublicInfo 为具名对象（未精简）
        JsonNode br = out.path("battle_results");
        assertTrue(br.path("playersPublicInfo").isObject(), "battle_results.playersPublicInfo 应存在");
        assertFalse(br.path("playersPublicInfo").isEmpty(), "playersPublicInfo 应有玩家");
        for (JsonNode v : br.path("playersPublicInfo")) {
            assertTrue(v.path("damage").isNumber(), "playersPublicInfo 玩家含 damage");
        }

        // 3. webFunction.battle_stats
        JsonNode webFunction = out.path("webFunction");
        JsonNode stats = webFunction.path("battle_stats");
        JsonNode teams = stats.path("teams");
        assertTrue(teams.size() >= 2, "应有双方队伍统计");
        for (JsonNode t : teams) {
            assertTrue(t.path("hp_pool").asDouble() > 0, "团队血池为正");
        }
        assertTrue(stats.path("players").size() >= 2, "应有个人统计");

        // 4. webFunction.battle_timeline（真实 MinimapExtractor 流式产物）
        JsonNode tl = webFunction.path("battle_timeline");
        assertTrue(tl.path("duration").asDouble() > 0, "时长为正");
        assertFalse(tl.path("players").isEmpty(), "有个人累计伤害");
        assertFalse(tl.path("teams").isEmpty(), "有团队时间线");
        assertTrue(tl.path("gap").path("health_gap").size() > 0, "血池差距有数据");
        assertTrue(tl.path("gap").path("score_gap").size() > 0, "分数差有数据");
    }

    @Test
    @DisplayName("多视角合并：dumpMergedJson 输出与单 replay 同构")
    void mergedReplayOutput() throws Exception {
        Path dir = Path.of("../temp/wg_15.6/热点");
        assumeTrue(Files.isDirectory(dir), "缺少 temp/wg_15.6/热点");
        List<Path> reps;
        try (var s = Files.walk(dir)) {
            reps = s.filter(p -> p.toString().endsWith(".wowsreplay")).sorted().limit(2).toList();
        }
        assumeTrue(reps.size() == 2, "热点目录下至少 2 份回放");

        var primary = ReplayFile.fromFile(reps.get(0), Path.of("../temp/wows-data"));
        assumeTrue(GameDataCache.resolveGameDataDir(primary) != null, "缺少匹配版本的游戏数据");

        var options = new ReplayDumper.Options(true, 7, false, null);
        var alt = ReplayFile.fromFile(reps.get(1), Path.of("../temp/wows-data"));
        String json = new ReplayDumper(List.of(primary, alt), options).dumpMergedJson();
        JsonNode out = JsonMapper.readTree(json);

        assertTrue(out.path("master_meta_id").asLong() > 0, "master_meta_id 为主视角玩家");
        assertTrue(out.path("players").isArray() && out.path("players").size() >= 2, "合并后有玩家");
        assertTrue(out.path("game_events").isArray(), "有 game_events");
        JsonNode wf = out.path("webFunction");
        assertTrue(wf.path("battle_stats").path("teams").size() >= 2, "双方队伍统计");
        assertTrue(wf.path("battle_timeline").path("duration").asDouble() > 0, "时间线时长");
        assertTrue(out.path("battle_results").path("playersPublicInfo").isObject(), "battle_results 已处理");
    }

    // ── 构造辅助 ────────────────────────────────────────────────────────

    private static ObjectNode playerNode(long metaId, long accountId, int team) {
        ObjectNode p = NF.deepCopy();
        p.put("meta_id", metaId);
        p.put("account_id", accountId);
        p.put("team_id", team);
        return p;
    }

    /** 扁平 results_info（对标 temp/compare/player-info.json）。 */
    private static ObjectNode rawResults(double damage, double exp, double maxHealth, boolean alive,
                                         double kills, double agroArt, double agroAir, double scouting,
                                         double receivedMainAp, String victimId, ObjectNode interaction) {
        ObjectNode raw = NF.deepCopy();
        raw.put("damage", damage);
        raw.put("exp", exp);
        raw.put("max_health", maxHealth);
        raw.put("is_alive", alive);
        raw.put("ships_killed", kills);
        raw.put("agro_art", agroArt);
        raw.put("agro_air", agroAir);
        raw.put("agro_dbomb", 0);
        raw.put("agro_tpd", 0);
        raw.put("scouting_damage", scouting);
        raw.put("received_damage_main_ap", receivedMainAp);
        raw.put("hits_main_he", 82);
        raw.put("shots_main_he", 292);
        raw.put("RIBBON_CITADEL", 2);
        raw.put("victory_points_own_ship_kill", -2500);
        raw.put("team_damage", 0);
        ObjectNode interactions = NF.deepCopy();
        interactions.set(victimId, interaction);
        raw.set("interactions", interactions);
        return raw;
    }

    /** A 打 200：主炮 HE 32872 + 起火 500，点亮 26502。 */
    private static ObjectNode interactionA() {
        ObjectNode n = NF.deepCopy();
        n.put("damage_main_he", 32872);
        n.put("hits_main_he", 10);
        n.put("damage_fire", 500);
        n.put("scouting_damage", 26502);
        n.put("ship_killed", 0);
        return n;
    }

    /** B 打 100：主炮 AP 12345，点亮 100。 */
    private static ObjectNode interactionB() {
        ObjectNode n = NF.deepCopy();
        n.put("damage_main_ap", 12345);
        n.put("hits_main_ap", 5);
        n.put("scouting_damage", 100);
        n.put("ship_killed", 0);
        return n;
    }

    private static MinimapOutput minimap() {
        List<DamageEntry> dmg = List.of(
            new DamageEntry(10f, 100, 200, 500f),
            new DamageEntry(15f, 9999, 200, 200f),   // 鱼雷，需经 shot_hits 归属
            new DamageEntry(20f, 100, 200, 300f),
            new DamageEntry(25f, 200, 100, 400f));
        List<ShotHitEntry> hits = List.of(new ShotHitEntry(15f, 100, 200, 1, 0, null, null, null, null));
        List<MinimapFrame> frames = List.of(
            frame(0f, entity(100, 0, 50000, 50000, true), entity(200, 1, 40000, 40000, true), 0, 0),
            frame(20f, entity(100, 0, 48000, 50000, true), entity(200, 1, 39000, 40000, true), 100, 80),
            frame(40f, entity(100, 0, 47000, 50000, true), entity(200, 1, 0, 40000, false), 120, 80));
        List<DeadShip> dead = List.of(new DeadShip(30f, 200, null, null));
        return new MinimapOutput(null, frames, null, dmg, hits, dead, null, null, null, null, null);
    }

    private static MinimapFrame frame(float clock, MinimapEntity eA, MinimapEntity eB,
                                      long scoreA, long scoreB) {
        return new MinimapFrame(clock, List.of(eA, eB), null, null, null, null, null, null, null,
            List.of(new TeamScoreEntry(0, scoreA), new TeamScoreEntry(1, scoreB)), null, null);
    }

    private static MinimapEntity entity(long metaId, int teamId, float health, float maxHealth, boolean alive) {
        return new MinimapEntity(metaId, 0f, 0f, 0f, true, teamId, health, maxHealth, alive, 0);
    }

    /** 递归找第一个 .wowsreplay。 */
    private static Path firstReplay(Path dir) throws java.io.IOException {
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".wowsreplay")).findFirst().orElse(null);
        }
    }
}
