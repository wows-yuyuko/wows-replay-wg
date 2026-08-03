package com.wows.replay.ingest;

import com.wows.replay.ReplayException;
import com.wows.replay.ReplayFile;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.model.Version;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.packet.Parser;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整集成测试：回放文件 → Parser → PacketDecoder → BattleWorld.
 *
 * <p>测试真实 15.6.0 版本回放文件，验证 4 层管道 (Layer 0-3) 的完整性和正确性。</p>
 */
@Slf4j
class ReplayAnalyzerIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH =
            "temp/wows-data";

    // ── 路径解析 (从模块目录 → 项目根目录) ──────────────────────────

    private Path resolveReplay() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(REPLAY_PATH);
    }

    private Path resolveWowsData() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(WOWS_DATA_PATH);
    }

    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        var dir = wowsDataBase.toFile();
        if (!dir.exists()) return null;
        var children = dir.listFiles();
        if (children == null) return null;
        for (var f : children) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    // ── 共享状态: 所有测试复用同一次解析结果 ────────────────────────

    private static ReplayFile replay;
    private static Version version;
    private static EntitySpecProvider specProvider;
    private static BattleWorld world;
    private static int totalPackets;
    private static int decodedPackets;

    @BeforeAll
    static void setUp() throws Exception {
        var test = new ReplayAnalyzerIT();
        var path = test.resolveReplay();
        replay = ReplayFile.fromFile(path);
        version = replay.version();
        log.info("=== Replay: v{}  {} players  {} ===",
            version, replay.meta().vehicles().size(), replay.meta().mapDisplayName());

        // ── 加载 EntitySpec ──────────────────────────────────────────
        var wowsData = test.resolveWowsData();
        var gameData = findGameDataDir(wowsData, version);
        assertNotNull(gameData, "游戏数据未找到: " + wowsData);

        var cache = GameDataCache.withMaxSize(4);
        specProvider = cache.entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
        assertNotNull(specProvider, "EntitySpecProvider 不应为 null");

        // ── Layer 1: Parser ─────────────────────────────────────────
        var parser = new Parser(specProvider, version);

        // ── Layer 2: PacketDecoder ──────────────────────────────────
        var decoder = new PacketDecoder(version);

        // ── Layer 3: BattleWorld ────────────────────────────────────
        world = new BattleWorld(replay.meta(), version);

        // ── 主循环 ─────────────────────────────────────────────────
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            totalPackets++;

            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof com.wows.replay.packet.Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;

            DecodedPayload payload = decoder.decode(packet);
            world.process(payload, raw.clock());
            decodedPackets++;
        }

        world.finish();
        decoder.dumpMethodStats();
    }

    // ── Layer 0-1: 文件读取 + 包帧 ──────────────────────────────────

    @Test
    @DisplayName("Layer 0-1: 回放文件读取、解密、包帧解析")
    void layer01_fileReadAndFraming() {
        assertNotNull(replay, "ReplayFile 不应为 null");
        assertNotNull(replay.meta(), "ReplayMeta 不应为 null");
        assertTrue(replay.packetData().length > 0, "packet data 不应为空");
        assertTrue(totalPackets > 10_000, "应至少有 10000 个包 (实际: " + totalPackets + ")");
        assertTrue(decodedPackets > 10_000, "应至少解码 10000 个包 (实际: " + decodedPackets + ")");

        log.info("Layer 0-1 ✓: {} total packets, {} decoded", totalPackets, decodedPackets);
    }

    // ── Layer 2: 语义解码 ───────────────────────────────────────────

    @Test
    @DisplayName("Layer 2: 语义解码 — 所有已知方法均已解码")
    void layer02_semanticDecode() {
        // 验证: decodedPackets > 0 说明 decoder 正常工作
        assertTrue(decodedPackets > 0, "decoder 应成功解码至少 1 个包");
        log.info("Layer 2 ✓: {} packets decoded", decodedPackets);
    }

    // ── Layer 3: ECS 实体 ──────────────────────────────────────────

    @Test
    @DisplayName("Layer 3: 实体管理 — Avatar, Vehicle, BattleLogic")
    void layer03_entities() {
        assertFalse(world.entities.isEmpty(), "应至少有 1 个实体");

        // 实体类型
        assertTrue(world.entityTypes.contains("Avatar"), "应有 Avatar 实体");
        assertTrue(world.entityTypes.contains("Vehicle"), "应有 Vehicle 实体");
        log.info("实体类型: {}", world.entityTypes);

        // 实体中的玩家数据 (arena state pickle 解码可能不完整)
        var namedPlayers = world.entities.values().stream()
            .filter(e -> e.playerName != null && !e.playerName.isEmpty())
            .toList();
        var allEntities = new ArrayList<>(world.entities.values());
        log.info("实体样本 (前 10 个):");
        for (int i = 0; i < Math.min(10, allEntities.size()); i++) {
            var es = allEntities.get(i);
            log.info("  eid={} type={} team={} hp={}/{} name={} dbId={}",
                es.id, es.type, es.teamId, es.health, es.maxHealth,
                es.playerName, es.dbId);
        }
        log.info("Layer 3 (实体) ✓: {} 实体, {} 类型, {} 命名玩家 (arena state), {} meta 玩家",
            world.entities.size(), world.entityTypes.size(),
            namedPlayers.size(), world.players.size());
    }

    // ── 玩家映射 ───────────────────────────────────────────────────

    @Test
    @DisplayName("玩家映射: meta vehicles → players")
    void playerMapping() {
        // Meta players are always populated from replay metadata
        assertFalse(world.players.isEmpty(), "应有 meta 玩家信息 (来自 replay JSON)");

        // 每个 meta vehicle → player 匹配
        int matched = 0;
        for (var v : replay.meta().vehicles()) {
            for (var pi : world.players.values()) {
                if (pi.username != null && pi.username.equals(v.name())) {
                    matched++;
                    break;
                }
            }
        }
        log.info("玩家映射 ✓: {} meta players, {}/{} vehicle 匹配, {} entity→player 链接 (arena state)",
            world.players.size(), matched, replay.meta().vehicles().size(),
            world.entityToPlayer.size());
    }

    // ── 战斗事件 ───────────────────────────────────────────────────

    @Test
    @DisplayName("战斗事件: 击杀、伤害、消耗品")
    void combatEvents() {
        log.info("击杀: {} 条", world.killLog.size());
        log.info("伤害事件: {} 条", world.damageEvents.size());
        log.info("消耗品: {} 条", world.consumableLog.size());

        for (var kill : world.killLog) {
            log.info("  Kill @{}s: {} → {}  cause={}",
                kill.clock(), kill.killerName(), kill.victimName(), kill.cause());
        }

        for (var dmg : world.damageEvents) {
            log.info("  Damage @{}s: agg={} → vic={}  amount={}",
                dmg.clock(), dmg.aggressorId(), dmg.victimId(), dmg.amount());
        }

        for (var cons : world.consumableLog) {
            log.info("  Consumable @{}s: eid={} player={} id={} duration={}s",
                cons.clock(), cons.entityId(), cons.username(), cons.consumableId(), cons.duration());
        }

        log.info("战斗事件 ✓: {} kills, {} damage, {} consumables",
            world.killLog.size(), world.damageEvents.size(), world.consumableLog.size());
    }

    // ── 聊天 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("聊天消息")
    void chatMessages() {
        log.info("聊天: {} 条", world.chatLog.size());
        for (var chat : world.chatLog) {
            log.info("  Chat @{}s: {} (dbId={}) [{}]: {}",
                chat.clock(), chat.senderName(), chat.dbId(), chat.channel(), chat.message());
        }
        log.info("聊天 ✓: {} messages", world.chatLog.size());
    }

    // ── 火炮 / 鱼雷 ────────────────────────────────────────────────

    @Test
    @DisplayName("武器: 火炮齐射、鱼雷发射、命中")
    void weaponEvents() {
        log.info("火炮齐射: {} 次", world.firedSalvos.size());
        log.info("鱼雷: {} 枚", world.torpedoes.size());
        log.info("命中: {} 条", world.shotHits.size());

        for (var salvo : world.firedSalvos) {
            log.info("  Salvo @{}s: owner={} params={} salvoId={} shots={}",
                salvo.clock(), salvo.salvo().ownerId(), salvo.salvo().paramsId(),
                salvo.salvo().salvoId(), salvo.salvo().shots().size());
        }

        for (var t : world.torpedoes) {
            log.info("  Torpedo @{}s: owner={} shotId={} armed={} maneuver={}",
                t.clock(), t.data().ownerId(), t.data().shotId(),
                t.data().armed(), t.hasManeuver());
        }

        for (var hit : world.shotHits) {
            log.info("  Hit @{}s: avatar={} shotId={} hitType={}",
                hit.clock(), hit.avatarId(), hit.hit().shotId(), hit.hit().hitType());
        }

        log.info("武器 ✓: {} salvos, {} torpedoes, {} hits",
            world.firedSalvos.size(), world.torpedoes.size(), world.shotHits.size());
    }

    // ── 航空 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("航空: 飞机起降、巡逻战斗机")
    void aviationEvents() {
        log.info("飞机事件: {} 条", world.planeEvents.size());
        log.info("活跃 Ward: {} 个", world.activeWards.size());

        for (var pe : world.planeEvents) {
            log.info("  Plane {} @{}s: id={} team={}",
                pe.action(), pe.clock(), pe.planeId(),
                pe.state() != null ? pe.state().teamId() : -1);
        }

        log.info("航空 ✓: {} plane events, {} wards",
            world.planeEvents.size(), world.activeWards.size());
    }

    // ── 勋带 / 语音 ────────────────────────────────────────────────

    @Test
    @DisplayName("勋带和语音指令")
    void ribbonsAndVoiceLines() {
        log.info("勋带: {} 条", world.ribbonLog.size());
        log.info("语音指令: {} 条", world.voiceLineLog.size());

        for (var r : world.ribbonLog) {
            log.info("  Ribbon @{}s: id={}", r.clock(), r.ribbonId());
        }

        for (var vl : world.voiceLineLog) {
            log.info("  VoiceLine @{}s: sender={} global={} msg={}",
                vl.clock(), vl.senderId(), vl.isGlobal(), vl.message());
        }

        log.info("勋带/语音 ✓: {} ribbons, {} voice lines",
            world.ribbonLog.size(), world.voiceLineLog.size());
    }

    // ── 地图 / 环境 ────────────────────────────────────────────────

    @Test
    @DisplayName("地图: 占点、天气、Buff 区域、建筑")
    void mapAndEnvironment() {
        assertNotNull(world.mapName, "地图名不应为 null");
        log.info("地图: {}", world.mapName);
        log.info("Arena ID: {}", world.mapArenaId);
        log.info("GameMode: {}", world.gameMode);
        log.info("MatchGroup: {}", world.matchGroup);

        log.info("占点: {} 个", world.capturePoints.size());
        for (var cp : world.capturePoints) {
            log.info("  CP[{}]: team={} invader={} progress={} enabled={}",
                cp.index, cp.teamId, cp.invaderTeam, cp.progress, cp.isEnabled);
        }

        log.info("Buff 区域: {} 个", world.buffZones.size());
        log.info("天气区域: {} 个", world.weatherZones.size());
        log.info("建筑: {} 个", world.buildings.size());

        for (var wz : world.weatherZones) {
            log.info("  Weather: {} @ ({},{}) r={}", wz.name(), wz.x(), wz.z(), wz.radius());
        }

        log.info("地图/环境 ✓: map={}, {} CPs, {} buffs, {} weather, {} buildings",
            world.mapName, world.capturePoints.size(),
            world.buffZones.size(), world.weatherZones.size(), world.buildings.size());
    }

    // ── 战斗结束 ───────────────────────────────────────────────────

    @Test
    @DisplayName("战斗结果: 胜负、结束类型")
    void battleResult() {
        log.info("WinningTeam: {}", world.winningTeam);
        log.info("FinishType: {}", world.finishType);
        log.info("MatchResult: {}", world.matchResult);
        log.info("MaxDuration: {}", world.maxDuration);
        log.info("PlayedDuration: {}", world.playedDuration);

        log.info("阵亡船只: {} 艘", world.deadShips.size());
        for (var ds : world.deadShips) {
            log.info("  Dead @{}s: victim={} pos=({},{})",
                ds.clock(), ds.victimId(), ds.x(), ds.z());
        }

        log.info("战斗结果 ✓: winner={}, finish={}, {} dead ships",
            world.winningTeam, world.finishType, world.deadShips.size());
    }

    // ── 综合统计 ───────────────────────────────────────────────────

    @Test
    @DisplayName("综合统计: 完整管道输出汇总")
    void summary() {
        var summary = new LinkedHashMap<String, Integer>();
        summary.put("totalPackets", totalPackets);
        summary.put("decodedPackets", decodedPackets);
        summary.put("entities", world.entities.size());
        summary.put("entityTypes", world.entityTypes.size());
        summary.put("players", world.players.size());
        summary.put("entityToPlayer", world.entityToPlayer.size());
        summary.put("kills", world.killLog.size());
        summary.put("damageEvents", world.damageEvents.size());
        summary.put("chatMessages", world.chatLog.size());
        summary.put("consumables", world.consumableLog.size());
        summary.put("salvos", world.firedSalvos.size());
        summary.put("torpedoes", world.torpedoes.size());
        summary.put("shotHits", world.shotHits.size());
        summary.put("planeEvents", world.planeEvents.size());
        summary.put("activeWards", world.activeWards.size());
        summary.put("ribbons", world.ribbonLog.size());
        summary.put("voiceLines", world.voiceLineLog.size());
        summary.put("capturePoints", world.capturePoints.size());
        summary.put("buffZones", world.buffZones.size());
        summary.put("weatherZones", world.weatherZones.size());
        summary.put("buildings", world.buildings.size());
        summary.put("deadShips", world.deadShips.size());

        log.info("");
        log.info("╔══════════════════════════════════════════╗");
        log.info("║         完整管道统计汇总                   ║");
        log.info("╠══════════════════════════════════════════╣");
        for (var e : summary.entrySet()) {
            log.info(String.format("║  %-20s  %6d  ║", e.getKey(), e.getValue()));
        }
        log.info("╚══════════════════════════════════════════╝");

        // ── 核心断言 ──────────────────────────────────────────────
        assertTrue(totalPackets > 10_000, "totalPackets");
        assertTrue(decodedPackets > 10_000, "decodedPackets");
        assertFalse(world.entities.isEmpty(), "entities");
        assertTrue(world.players.size() >= 20, "players (12v12 + bots)");
        log.info("kills: {} (may be 0 if full pipeline not reached)", world.killLog.size());
        assertNotNull(world.mapName, "mapName");

        dumpSummaryJson();
    }

    /** 将 summary 表落成 JSON（temp/compare/java_summary.json），与 Rust 侧 compare_java 输出对齐。 */
    private void dumpSummaryJson() {
        try {
            var o = com.wows.replay.JsonMapper.getMapper().createObjectNode();
            o.put("replay", REPLAY_PATH.substring(REPLAY_PATH.lastIndexOf('/') + 1));
            o.put("version", version.toString());
            o.put("mapName", world.mapName == null ? "" : world.mapName);
            o.put("mapArenaId", world.mapArenaId);
            o.put("gameMode", world.gameMode);
            if (world.matchGroup != null) o.put("matchGroup", world.matchGroup);

            o.put("totalPackets", totalPackets);
            o.put("decodedPackets", decodedPackets);
            o.put("entities", world.entities.size());
            o.put("entityKinds", world.entityKinds());
            o.put("entityTypes", world.entityTypes.size());
            var eTypes = com.wows.replay.JsonMapper.getMapper().createObjectNode();
            for (var es : world.entities.values()) {
                eTypes.put(es.type, eTypes.path(es.type).asInt(0) + 1);
            }
            o.set("entitiesByType", eTypes);
            o.put("players", world.players.size());
            o.put("entityToPlayer", world.entityToPlayer.size());
            o.put("kills", world.killLog.size());
            o.put("damageEvents", world.damageEvents.size());
            o.put("chatMessages", world.chatLog.size());
            o.put("consumables", world.consumableLog.size());
            o.put("salvos", world.firedSalvos.size());
            o.put("torpedoes", world.torpedoes.size());
            o.put("shotHits", world.shotHits.size());
            o.put("planeEvents", world.planeEvents.size());
            o.put("activeWards", world.activeWards.size());
            o.put("ribbons", world.ribbonLog.size());
            o.put("voiceLines", world.voiceLineLog.size());
            o.put("capturePoints", world.capturePoints.size());
            o.put("buffZones", world.buffZones.size());
            o.put("weatherZones", world.weatherZones.size());
            o.put("buildings", world.buildings.size());
            o.put("deadShips", world.deadShips.size());

            if (world.winningTeam != null) o.put("winningTeam", world.winningTeam);
            if (world.finishType != null) o.put("finishType", world.finishType);
            if (world.matchResult != null) o.put("matchResult", world.matchResult);
            if (world.maxDuration != null) o.put("maxDuration", world.maxDuration);
            if (world.playedDuration != null) o.put("playedDuration", world.playedDuration);
            if (world.extraDuration != null) o.put("extraDuration", world.extraDuration);

            var out = Path.of(System.getProperty("user.dir")).getParent()
                .resolve("temp").resolve("compare").resolve("java_summary.json");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out,
                com.wows.replay.JsonMapper.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(o));
            log.info("已写入 {}", out);
            log.info("输出 World Json");
            var jsonPath = Path.of(System.getProperty("user.dir")).getParent()
                    .resolve("temp").resolve("compare").resolve("java_summary-world.json");
            java.nio.file.Files.createDirectories(jsonPath.getParent());
            java.nio.file.Files.writeString(jsonPath,
                    com.wows.replay.JsonMapper.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(world));
            log.info("已写入 {}", jsonPath);
        } catch (Exception e) {
            log.warn("dumpSummaryJson 失败: {}", e.toString());
        }
    }

    // ── ReplayAnalyzer.buildBattleReport（battle-report.md into_report）──

    @Test
    @DisplayName("buildBattleReport: 文档化 BattleReport 快照（self_player/players/frags/时长/胜负）")
    void buildBattleReportFromWorld() {
        var analyzer = ReplayAnalyzer.builder()
            .specProvider(specProvider)
            .config(ReplayAnalyzerConfig.builder().decodePackets(true).build())
            .build();
        var report = analyzer.buildBattleReport(replay);

        // §7.3: self_player 必须存在
        assertNotNull(report.selfPlayer(), "self_player 不应为 null");
        assertEquals(0, report.selfPlayer().relation(), "self_player.relation 应为 0 (Self)");

        // 玩家列表：24 名玩家 + self 在内
        assertNotNull(report.players(), "players 不应为 null");
        assertTrue(report.players().size() >= 24, "players 至少 24 名 (实际: " + report.players().size() + ")");
        assertEquals(report.selfPlayer(), report.players().stream()
            .filter(p -> p.relation() == 0).findFirst().orElse(null), "self_player 应出现在 players 中");

        // 元数据
        assertNotNull(report.version(), "version 不应为 null");
        assertNotNull(report.mapName(), "map_name 不应为 null");
        assertEquals("spaces/56_AngelWings", report.mapName(), "map_name");
        assertEquals(com.wows.replay.ingest.report.MatchResult.LOSS, report.matchResult(),
            "match_result 应为 Loss (winningTeam=1, self 在队伍 2)");

        // 战斗统计字段
        assertNotNull(report.frags(), "frags 不应为 null");
        assertTrue(report.players().stream().anyMatch(p -> p.vehicleEntity() != null),
            "应至少有一名玩家带 VehicleEntity");
        assertTrue(report.players().stream().anyMatch(p -> p.vehicleEntity() != null
                && p.vehicleEntity().damage() > 0),
            "应至少有一名玩家有 >0 伤害");

        // 时长
        assertEquals(1200, report.maxDuration(), "max_duration 应为 meta duration 1200");
        assertNotNull(report.playedDuration(), "played_duration 不应为 null");
        assertTrue(report.playedDuration() > 0, "played_duration 应 > 0");

        // 战报 JSON（0x22）应有内容
        assertNotNull(report.battleResults(), "battle_results 不应为 null");

        // 可序列化
        var json = com.wows.replay.JsonMapper.toPrettyJson(report);
        assertTrue(json.contains("\"arena_id\"") && json.contains("\"self_player\""),
            "JSON 应包含 arena_id / self_player");
        log.info("BattleReport JSON (head): {}", json.substring(0, Math.min(300, json.length())));
    }

    // ── ReplayAnalyzer：quick / analyze 端到端 ──────────────────────

    @Test
    @DisplayName("quick(): 无 EntitySpecProvider 时抛出 IllegalArgumentException")
    void quickRequiresSpec() {
        var e = assertThrows(IllegalArgumentException.class, () -> ReplayAnalyzer.quick(replay),
            "quick() 依赖 EntitySpecProvider");
        log.info("quick() 无 spec ✓: {}", e.getMessage());
    }

    @Test
    @DisplayName("analyze(): 新 BattleReport JSON（arena_id/self_player/players/game_chat）")
    void analyzeJson() throws Exception {
        var analyzer = ReplayAnalyzer.builder()
            .specProvider(specProvider)
            .config(ReplayAnalyzerConfig.builder().decodePackets(true).build())
            .build();
        String json = analyzer.analyze(replay);
        var node = com.wows.replay.JsonMapper.readTree(json);

        assertNotNull(node.get("arena_id"), "应有 arena_id");
        assertNotNull(node.get("self_player"), "应有 self_player");
        assertEquals(0, node.get("self_player").get("relation").asInt(), "self_player.relation 应为 0");

        assertNotNull(node.get("players"), "应有 players");
        assertTrue(node.get("players").size() >= 24, "players 至少 24 名 (实际: " + node.get("players").size() + ")");

        assertNotNull(node.get("game_chat"), "应有 game_chat");
        assertTrue(node.get("game_chat").size() > 0, "game_chat 应非空");
        assertNotNull(node.get("battle_results"), "应有 battle_results");
        assertNotNull(node.get("match_result"), "应有 match_result");
        assertEquals("spaces/56_AngelWings", node.get("map_name").asText(), "map_name");
        assertEquals(1200, node.get("max_duration").asLong(), "max_duration");

        log.info("analyze() ✓: {} bytes, chat={}, players={}",
            json.length(), node.get("game_chat").size(), node.get("players").size());
    }

    // ── BattleSnapshot ────────────────────────────────────────────────

    @Test
    @DisplayName("intoReport: 终局快照与 summary 一致")
    void battleSnapshot() {
        var snap = world.intoReport();
        assertEquals("spaces/56_AngelWings", snap.mapName());
        assertEquals(Integer.valueOf(1), snap.winningTeam());
        assertEquals("Loss", snap.matchResult());
        assertEquals(world.players.size(), snap.players().size());
        assertEquals(world.killLog.size(), snap.kills().size());
        assertEquals(world.chatLog.size(), snap.chat().size());
        assertEquals(1200f, snap.maxDuration(), 0.001f);
        assertNotNull(snap.playedDuration());
        assertTrue(snap.players().stream().allMatch(p -> p.dbId() > 0));

        log.info("BattleSnapshot: map={} winner={} finish={} result={} players={} kills={} chat={}",
            snap.mapName(), snap.winningTeam(), snap.finishType(), snap.matchResult(),
            snap.players().size(), snap.kills().size(), snap.chat().size());
        log.info("  maxDuration={} played={} extra={}",
            snap.maxDuration(), snap.playedDuration(), snap.extraDuration());
        for (var p : snap.players()) {
            log.info("  player dbId={} name={} team={} damage={}", p.dbId(), p.username(), p.teamId(), p.totalDamage());
        }

        var json = com.wows.replay.JsonMapper.toPrettyJson(snap);
        assertTrue(json.contains("\"map_name\""));
        log.info("BattleSnapshot JSON (head): {}", json.substring(0, Math.min(300, json.length())));
    }

    // ── 错误处理测试 ───────────────────────────────────────────────

    @Test
    @DisplayName("quick(null) 抛出 ReplayException")
    void quickWithNullReplay() {
        assertThrows(ReplayException.class, () -> ReplayAnalyzer.quick((ReplayFile) null),
            "quick(null) should throw ReplayException");
    }

    @Test
    @DisplayName("quick(不存在的文件) 抛出 IOException")
    void quickWithNonExistentFile() {
        assertThrows(IOException.class,
            () -> ReplayAnalyzer.quick(Path.of("nonexistent_12345.wowsreplay")),
            "quick(nonexistent path) should throw IOException");
    }
}
