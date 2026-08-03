package com.wows.replay.dumper;

import com.wows.replay.ReplayFile;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.ingest.report.MatchResult;
import com.wows.replay.model.Version;
import com.wows.replay.packet.Parser;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplayAnalyzer 集成测试：战报装配 + 序列化输出（replay-dumper 管线层）。
 *
 * <p>BattleWorld 摄入测试在 ingest 模块的 {@code BattleWorldIT}。</p>
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

    // ── BattleReport 装配 ────────────────────────────────────────────

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
