package com.wows.replay.ingest;

import com.wows.replay.ReplayException;
import com.wows.replay.ReplayFile;
import com.wows.replay.ReplayVersionMismatchException;
import com.wows.replay.model.Version;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplayAnalyzer API 行为测试：quick / analyze / 版本门禁 / 错误处理。
 *
 * <p>管线核心（BattleWorld 摄入 / BattleReport 装配）的集成测试见 {@code ReplayAnalyzerIT}。</p>
 */
@Slf4j
class ReplayAnalyzerTest {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH = "temp/wows-data";

    private static ReplayFile replay;
    private static Version version;
    private static EntitySpecProvider specProvider;

    @BeforeAll
    static void setUp() throws Exception {
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH));
        version = replay.version();

        var gameData = findGameDataDir(resolve(WOWS_DATA_PATH), version);
        assertNotNull(gameData, "游戏数据未找到: " + resolve(WOWS_DATA_PATH));
        specProvider = GameDataCache.withMaxSize(4)
            .entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
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

    private static ReplayAnalyzer analyzerWithSpec() {
        return ReplayAnalyzer.builder()
            .specProvider(specProvider)
            .config(ReplayAnalyzerConfig.builder().decodePackets(true).build())
            .build();
    }

    // ── analyze / quick ──────────────────────────────────────────────

    @Test
    @DisplayName("analyze(): BattleReport JSON（arena_id/self_player/players/game_chat）")
    void analyzeJson() throws Exception {
        String json = analyzerWithSpec().analyze(replay);
        var node = com.wows.replay.JsonMapper.readTree(json);

        assertNotNull(node.get("arena_id"), "应有 arena_id");
        assertNotNull(node.get("self_player"), "应有 self_player");
        assertEquals(0, node.get("self_player").get("relation").asInt(), "self_player.relation 应为 0");
        assertTrue(node.get("players").size() >= 24, "players 至少 24 名");
        assertTrue(node.get("game_chat").size() > 0, "game_chat 应非空");
        assertEquals("spaces/56_AngelWings", node.get("map_name").asText(), "map_name");

        log.info("analyze() ✓: {} bytes", json.length());
    }

    @Test
    @DisplayName("quick(): 无 EntitySpecProvider 时抛出 IllegalArgumentException")
    void quickRequiresSpec() {
        assertThrows(IllegalArgumentException.class, () -> ReplayAnalyzer.quick(replay),
            "quick() 依赖 EntitySpecProvider");
    }

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

    // ── §12.4.1 版本门禁 ────────────────────────────────────────────

    @Test
    @DisplayName("版本门禁: expectedBuild 不匹配抛 ReplayVersionMismatchException")
    void versionGateRejectsMismatch() {
        var analyzer = ReplayAnalyzer.builder()
            .config(ReplayAnalyzerConfig.builder().expectedBuild("99999999").build())
            .build();
        assertThrows(ReplayVersionMismatchException.class, () -> analyzer.buildBattleReport(replay),
            "expectedBuild 不匹配应拒绝解析");
    }

    @Test
    @DisplayName("版本门禁: build 匹配时先过门禁，再进入管线（无 spec → IllegalArgumentException）")
    void versionGatePassesOnMatch() {
        var analyzer = ReplayAnalyzer.builder()
            .config(ReplayAnalyzerConfig.builder()
                .expectedBuild(String.valueOf(replay.version().build()))
                .build())
            .build();
        // 若门禁未通过会抛 ReplayVersionMismatchException，assertThrows 将失败；
        // 此处抛 IllegalArgumentException 说明已过门禁、进入 spec 前置检查。
        assertThrows(IllegalArgumentException.class, () -> analyzer.buildBattleReport(replay));
    }
}
