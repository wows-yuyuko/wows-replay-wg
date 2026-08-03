package com.wows.replay.dumper.minimap;

import com.wows.replay.ReplayFile;
import com.wows.replay.model.Version;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimap 数据提取集成测试（Single 模式，docs/replay-dumper-minimap.md §4）。
 */
@Slf4j
class MinimapExtractorIT {

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

    @Test
    @DisplayName("MinimapExtractor: 帧/事件流/终局状态提取正确且可序列化")
    void minimapExtract() throws Exception {
        var out = new MinimapExtractor(specProvider, replay, 7).extract();

        assertNotNull(out.arenaId(), "arena_id 不应为 null");
        assertFalse(out.frames().isEmpty(), "应有位置帧");
        assertTrue(out.frames().stream().anyMatch(f -> !f.entities().isEmpty()),
            "应存在含船位实体的帧");
        assertFalse(out.damageEvents().isEmpty(), "应有伤害事件");
        assertFalse(out.firingEvents().isEmpty(), "应有齐射事件");
        assertFalse(out.shotHits().isEmpty(), "应有命中事件");
        assertFalse(out.deadShips().isEmpty(), "应有沉船");
        assertNotNull(out.scoringRules(), "应有 scoring_rules");
        assertNotNull(out.winningTeam(), "应有 winning_team");
        assertTrue(out.frames().stream().anyMatch(f -> f.teamScores().size() >= 2), "应有队伍比分");

        // 帧内归一化坐标范围检查
        var entity = out.frames().stream().flatMap(f -> f.entities().stream())
            .findFirst().orElse(null);
        assertNotNull(entity, "应至少有一个船位实体");
        assertTrue(entity.x() >= -1.5f && entity.x() <= 1.5f, "x 应为归一化坐标");
        assertTrue(entity.y() >= -1.5f && entity.y() <= 1.5f, "y 应为归一化坐标");

        // 输出到 temp/compare 供与 Rust 对拍
        var json = com.wows.replay.JsonMapper.toPrettyJson(out);
        assertTrue(json.contains("\"arena_id\"") && json.contains("\"frames\""),
            "JSON 应包含 arena_id / frames");
        writeCompare("java_minimap.json", out);
        log.info("Minimap ✓: {} frames, {} firing, {} damage, {} hits, {} dead",
            out.frames().size(), out.firingEvents().size(),
            out.damageEvents().size(), out.shotHits().size(), out.deadShips().size());
    }

    private static void writeCompare(String fileName, Object value) throws Exception {
        var out = resolve("temp/compare").resolve(fileName);
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out,
            com.wows.replay.JsonMapper.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
        log.info("已写入 {}", out);
    }
}
