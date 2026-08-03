package com.wows.replay.ingest;

import com.wows.replay.ReplayFile;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.ingest.report.BattleReportBuilder;
import com.wows.replay.ingest.report.MatchResult;
import com.wows.replay.model.GameClock;
import com.wows.replay.model.Version;
import com.wows.replay.packet.Parser;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：回放文件 → Parser → PacketDecoder → BattleWorld → BattleReport。
 *
 * <p>只覆盖核心管线的两个产出：{@link BattleWorld} 摄入与 {@link com.wows.replay.ingest.report.BattleReport}
 * 装配。ReplayAnalyzer 的 API 行为（quick/analyze/版本门禁/错误处理）见 {@code ReplayAnalyzerTest}。</p>
 */
@Slf4j
class ReplayAnalyzerIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH = "temp/wows-data";

    private static ReplayFile replay;
    private static Version version;
    private static EntitySpecProvider specProvider;
    private static BattleWorld world;

    @BeforeAll
    static void setUp() throws Exception {
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH));
        version = replay.version();

        var gameData = findGameDataDir(resolve(WOWS_DATA_PATH), version);
        assertNotNull(gameData, "游戏数据未找到: " + resolve(WOWS_DATA_PATH));
        specProvider = GameDataCache.withMaxSize(4)
            .entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);

        // 完整管线：Parser → PacketDecoder → BattleWorld
        var parser = new Parser(specProvider, version);
        var decoder = new PacketDecoder(version);
        world = new BattleWorld(replay.meta(), version);
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof com.wows.replay.packet.Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());
        }
        world.finish();
        log.info("=== 管线完成: {} players, {} entities, {} kills, {} chat, {} damage ===",
            world.players.size(), world.entities.size(), world.killLog.size(),
            world.chatLog.size(), world.damageEvents.size());
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        var children = wowsDataBase.toFile().listFiles();
        if (children == null) return null;
        for (var f : children) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    // ── BattleWorld ──────────────────────────────────────────────────

    @Test
    @DisplayName("BattleWorld: 实体/玩家/地图/战斗结果摄入正确")
    void battleWorldIngest() {
        assertFalse(world.entities.isEmpty(), "应摄入实体");
        assertTrue(world.entityTypes.contains("Avatar") && world.entityTypes.contains("Vehicle"),
            "应有 Avatar / Vehicle 实体");
        assertTrue(world.players.size() >= 20, "players 12v12 + bots (实际: " + world.players.size() + ")");
        assertFalse(world.chatLog.isEmpty(), "应有聊天消息");
        assertFalse(world.damageEvents.isEmpty(), "应有伤害事件");

        assertEquals("spaces/56_AngelWings", world.mapName, "map_name");
        assertEquals(Integer.valueOf(1), world.winningTeam, "winning_team");
        assertEquals("Loss", world.matchResult, "match_result");
        assertEquals(1200f, world.maxDuration, 0.001f, "max_duration");
        assertNotNull(world.playedDuration, "played_duration 不应为 null");
        log.info("BattleWorld ✓: {} kills, {} damage, {} chat, {} players",
            world.killLog.size(), world.damageEvents.size(), world.chatLog.size(), world.players.size());
    }

    @Test
    @DisplayName("BattleWorld.intoReport: 终局快照与内部状态一致且可序列化")
    void battleSnapshot() {
        var snap = world.intoReport();
        assertEquals("spaces/56_AngelWings", snap.mapName());
        assertEquals(Integer.valueOf(1), snap.winningTeam());
        assertEquals("Loss", snap.matchResult());
        assertEquals(world.players.size(), snap.players().size());
        assertEquals(world.killLog.size(), snap.kills().size());
        assertEquals(world.chatLog.size(), snap.chat().size());
        assertEquals(1200f, snap.maxDuration(), 0.001f);
        assertTrue(snap.playedDuration() != null && snap.playedDuration() > 0, "played_duration 应 > 0");
        assertTrue(snap.players().stream().allMatch(p -> p.dbId() > 0));

        var json = com.wows.replay.JsonMapper.toPrettyJson(snap);
        assertTrue(json.contains("\"map_name\""));
        log.info("BattleSnapshot ✓: players={}, kills={}, chat={}",
            snap.players().size(), snap.kills().size(), snap.chat().size());
    }

    @Test
    @DisplayName("BattleWorld 时钟推进: clock>0 || 当前==0 才更新，clock=0 不倒退（§12.4.4）")
    void clockAdvancement() {
        var w = new BattleWorld(replay.meta(), version);

        w.process(new DecodedPayload.RibbonPayload(1), GameClock.ZERO);
        assertEquals(0f, w.currentClock().seconds());

        w.process(new DecodedPayload.RibbonPayload(2), new GameClock(5f));
        assertEquals(5f, w.currentClock().seconds());

        w.process(new DecodedPayload.RibbonPayload(3), GameClock.ZERO);
        assertEquals(5f, w.currentClock().seconds(), "clock=0 的包不应倒退已推进的时钟");

        assertEquals(5f, w.ribbonLog().getLast().clock(), "事件时钟应取推进后的时钟");
    }

    @Test
    @DisplayName("BattleWorld 常量兜底: null → 空实现，gameModeName 进入 report 兜底链（§12.4.3）")
    void constantsFallback() {
        var worldNull = new BattleWorld(replay.meta(), version, null);
        assertNotNull(worldNull.constants(), "无 GameConstants 时应用默认空实现");
        assertEquals(Optional.empty(), worldNull.constants().gameModeName(1));

        var fake = new GameConstantsProvider() {
            @Override public Optional<String> gameModeName(int id) { return Optional.of("自定义模式"); }
        };
        var report = new BattleReportBuilder(new BattleWorld(replay.meta(), version, fake), replay.meta()).build();
        assertEquals("自定义模式", report.gameMode(), "本地化缺失时用 constants.gameModeName");

        var fallback = new BattleReportBuilder(new BattleWorld(replay.meta(), version), replay.meta()).build();
        assertEquals(replay.meta().scenario(), fallback.gameMode(), "常量缺失时回退到原始 scenario");
    }

    // ── BattleReport ─────────────────────────────────────────────────

    @Test
    @DisplayName("BattleReport: buildBattleReport 快照正确且可序列化")
    void battleReport() throws Exception {
        var report = ReplayAnalyzer.builder()
            .specProvider(specProvider)
            .config(ReplayAnalyzerConfig.DEFAULT)
            .build()
            .buildBattleReport(replay);

        // §7.3: self_player 必须存在且 relation=0
        assertNotNull(report.selfPlayer(), "self_player 不应为 null");
        assertEquals(0, report.selfPlayer().relation(), "self_player.relation 应为 0");
        assertEquals(report.selfPlayer(), report.players().stream()
            .filter(p -> p.relation() == 0).findFirst().orElse(null),
            "self_player 应出现在 players 中");

        assertTrue(report.players().size() >= 24, "players 至少 24 名 (实际: " + report.players().size() + ")");
        assertEquals("spaces/56_AngelWings", report.mapName(), "map_name");
        assertEquals(MatchResult.LOSS, report.matchResult(), "match_result");
        assertEquals(1200, report.maxDuration(), "max_duration");
        assertTrue(report.playedDuration() > 0, "played_duration 应 > 0");
        assertNotNull(report.battleResults(), "battle_results 不应为 null");
        assertTrue(report.players().stream().anyMatch(p -> p.vehicleEntity() != null
                && p.vehicleEntity().damage() > 0),
            "应至少有一名玩家有 >0 伤害");

        var json = com.wows.replay.JsonMapper.toPrettyJson(report);
        assertTrue(json.contains("\"arena_id\"") && json.contains("\"self_player\""),
            "JSON 应包含 arena_id / self_player");
        log.info("BattleReport ✓: {} players, JSON {} bytes", report.players().size(), json.length());
    }

    // ── 序列化输出 ──────────────────────────────────────────────────

    @Test
    @DisplayName("序列化输出: BattleReport / BattleSnapshot 写入 temp/compare")
    void dumpToCompare() throws Exception {
        var report = ReplayAnalyzer.builder()
            .specProvider(specProvider)
            .config(ReplayAnalyzerConfig.DEFAULT)
            .build()
            .buildBattleReport(replay);

        writeCompare("java_report.json", report);
        writeCompare("java_summary-world.json", world.intoReport());
    }

    private static void writeCompare(String fileName, Object value) throws Exception {
        var out = resolve("temp/compare").resolve(fileName);
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out,
            com.wows.replay.JsonMapper.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
        log.info("已写入 {}", out);
    }
}
