package com.shinoaki.wowsreplay.dumper.minimap;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双回放 minimap 移动数据合并集成测试：wg_15.6/热点 双方全员 18 视角。
 * frames 只用主/副两份（各提本方、敌我 side 相对主视角），其余流全量用户去重。
 */
@Slf4j
class MinimapMergerIT {

    private static final String WOWS_DATA_PATH = "temp/wows-data";
    private static final String MULTI_VIEW_DIR = "temp/wg_15.6/热点";

    private static EntitySpecProvider specProvider;
    private static GameConstantsProvider constants;
    private static List<Path> files;

    @BeforeAll
    static void setUp() throws Exception {
        files = multiViewFiles();
        var first = ReplayFile.fromFile(files.get(0));
        var gameData = findGameDataDir(resolve(WOWS_DATA_PATH), first.version());
        assertNotNull(gameData, "游戏数据未找到");
        var cache = GameDataCache.withMaxSize(4);
        specProvider = cache.entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
        constants = cache.constants(GameDataCache.VersionKey.from(gameData), gameData);
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        try (var entries = Files.list(wowsDataBase)) {
            for (var dir : entries.toList()) {
                if (Files.isDirectory(dir) && dir.getFileName().toString().startsWith(prefix)) {
                    return dir.resolve("live");
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static List<Path> multiViewFiles() throws IOException {
        return Files.list(resolve(MULTI_VIEW_DIR))
            .filter(p -> p.toString().endsWith(".wowsreplay"))
            .sorted()
            .toList();
    }

    @Test
    @DisplayName("双回放 minimap 合并：帧含敌我双方 side，其余流全量去重")
    void mergeMinimapFriendlyPerSide() throws Exception {
        var replays = files.stream().map(p -> {
            try {
                return ReplayFile.fromFile(p);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        var out = new MinimapMerger(specProvider, constants, replays, 7).merge();

        assertNotNull(out, "应有合并输出");
        assertFalse(out.frames().isEmpty(), "应有合并帧");

        boolean hasFriendly = false;
        boolean hasEnemy = false;
        int entities = 0;
        for (var f : out.frames()) {
            for (var e : f.entities()) {
                entities++;
                if (e.side() == 0 || e.side() == 1) hasFriendly = true;
                if (e.side() == 2) hasEnemy = true;
            }
        }
        assertTrue(entities > 0, "帧里应有船位");
        assertTrue(hasFriendly, "应有本方（side 0/1）移动数据");
        assertTrue(hasEnemy, "应有敌方（side 2）移动数据");

        // 其余流来自全量用户去重
        log.info("合并 minimap: frames={} entities={} firing={} damage={} shot_hits={} dead_ships={}",
            out.frames().size(), entities, out.firingEvents().size(), out.damageEvents().size(),
            out.shotHits().size(), out.deadShips().size());
        assertFalse(out.damageEvents().isEmpty(), "应有全量去重的伤害事件");
        assertNotNull(JsonMapper.toJson(out), "Merged minimap 应可序列化");
    }
}
