package com.shinoaki.wowsreplay.dumper.minimap;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
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
    private static EntitySpecProvider specProvider;

    @BeforeAll
    static void setUp() throws Exception {
        var base = resolve(WOWS_DATA_PATH);
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH), base);

        assertNotNull(GameDataCache.resolveGameDataDir(replay), "游戏数据未找到: " + base);
        specProvider = GameDataCache.withMaxSize(4).entitySpecs(replay);
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    private static void writeCompare(String fileName, Object value) throws Exception {
        var out = resolve("temp/compare").resolve(fileName);
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out,
            JsonMapper.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
        log.info("已写入 {}", out);
    }

    @Test
    @DisplayName("MinimapExtractor: 压缩输出（结构同全量，entities 为移动增量，含烟雾/点亮等）")
    void minimapCompressedExtract() throws Exception {
        var out = new MinimapExtractor(specProvider, replay).extractCompressed();

        assertTrue(out.movementDelta(), "movement_delta 应为 true");
        assertNotNull(out.arenaId(), "arena_id 不应为 null");
        assertFalse(out.frames().isEmpty(), "应有帧（结构同全量）");
        assertFalse(out.firingEvents().isEmpty(), "应有齐射事件");
        assertFalse(out.damageEvents().isEmpty(), "应有伤害事件");
        assertFalse(out.shotHits().isEmpty(), "应有命中事件");
        assertFalse(out.deadShips().isEmpty(), "应有沉船");
        assertNotNull(out.scoringRules(), "应有 scoring_rules");
        assertNotNull(out.winningTeam(), "应有 winning_team");
        assertTrue(out.frames().stream().anyMatch(f -> f.teamScores().size() >= 2), "应有队伍比分");
        // 占领点 progress 应被捕获（capture-point-audit.md §3：NestedPropertyUpdate 值解码修复后非零）
        assertTrue(out.frames().stream().flatMap(f -> f.capturePoints().stream())
                .anyMatch(cp -> cp.progress() > 0f), "占领点 progress 应被捕获");

        // 每帧仍有帧内补充状态（planes/torpedoes/smoke 等结构保留）
        assertTrue(out.frames().stream().anyMatch(f -> f.planes() != null && !f.planes().isEmpty())
                || out.frames().stream().anyMatch(f -> f.smokeScreens() != null && !f.smokeScreens().isEmpty())
                || out.frames().stream().anyMatch(f -> f.torpedoes() != null && !f.torpedoes().isEmpty()),
            "帧内补充状态（planes/smoke/torpedoes）应保留");

        // 增量性：同 meta_id 相邻两条必发生显著变化（位置 ≥ 1e-2 / 航向 ≥ 1° / 可见 / 血量 / 存活 / 敌我）
        var byMeta = out.frames().stream().flatMap(f -> f.entities().stream()).collect(
            java.util.stream.Collectors.groupingBy(
                MinimapOutput.MinimapEntity::metaId, java.util.stream.Collectors.toList()));
        for (var list : byMeta.values()) {
            for (int i = 1; i < list.size(); i++) {
                var a = list.get(i - 1);
                var b = list.get(i);
                boolean changed = Math.abs(a.x() - b.x()) >= 1e-2f
                    || Math.abs(a.y() - b.y()) >= 1e-2f
                    || Math.abs(a.heading() - b.heading()) >= 1.0f
                    || a.visible() != b.visible()
                    || a.visibilityFlags() != b.visibilityFlags()
                    || a.isInvisible() != b.isInvisible()
                    || a.teamId() != b.teamId()
                    || a.health() != b.health()
                    || a.maxHealth() != b.maxHealth()
                    || a.isAlive() != b.isAlive()
                    || a.side() != b.side();
                assertTrue(changed, "同 meta_id 相邻增量应显著变化: " + a + " -> " + b);
            }
        }

        // 位置为归一化坐标
        assertTrue(out.frames().stream().flatMap(f -> f.entities().stream()).allMatch(e ->
                e.x() >= -1.5f && e.x() <= 1.5f && e.y() >= -1.5f && e.y() <= 1.5f),
            "x/y 应为归一化坐标");

        // 输出到 temp/compare 供外部程序/对拍使用
        var json = JsonMapper.toPrettyJson(out);
        assertTrue(json.contains("\"movement_delta\"") && json.contains("\"smoke_screens\""),
            "JSON 应含 movement_delta / smoke_screens");
        writeCompare("java_minimap_compressed.json", out);
        log.info("Minimap compressed ✓: {} frames, {} firing, {} damage, {} hits, {} dead",
            out.frames().size(), out.firingEvents().size(),
            out.damageEvents().size(), out.shotHits().size(), out.deadShips().size());
    }
}
