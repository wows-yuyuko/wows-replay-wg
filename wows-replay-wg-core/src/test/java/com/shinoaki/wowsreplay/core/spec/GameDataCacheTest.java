package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.model.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GameDataCache#resolveGameDataDir(Path, Version)} 的两级匹配规则
 * （精确 major.minor.patch → 同 major.minor 回退）。
 */
class GameDataCacheTest {

    /** 造一个 data-*.build/live 目录（内容无关，只看目录结构）。 */
    private static void dataDir(Path base, String name) throws IOException {
        Files.createDirectories(base.resolve(name).resolve("live"));
    }

    @Test
    void exactMatchWinsOverFallback(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.7.0.0.13015811");
        dataDir(base, "data-15.8.0.0.13187581");
        dataDir(base, "data-15.8.1.0.13243917");

        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 1, 13243917));

        assertEquals(base.resolve("data-15.8.1.0.13243917").resolve("live"), resolved);
    }

    @Test
    void fallsBackToSameMinorWhenPatchMissing(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.7.0.0.13015811");
        dataDir(base, "data-15.8.0.0.13187581");

        // 国服回放 15.8.1，本地只有 15.8.0 → 回退使用 15.8.0
        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 1, 13243917));

        assertEquals(base.resolve("data-15.8.0.0.13187581").resolve("live"), resolved);
    }

    @Test
    void fallbackPicksNearestPatch(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.8.0.0.13187581");
        dataDir(base, "data-15.8.4.0.13200000");

        // 15.8.3 距 15.8.4 更近（1）than 15.8.0（3）
        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 3, 1));

        assertEquals(base.resolve("data-15.8.4.0.13200000").resolve("live"), resolved);
    }

    @Test
    void fallbackTiePrefersOlderPatch(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.8.0.0.13187581");
        dataDir(base, "data-15.8.2.0.13200000");

        // 15.8.1 距两侧都是 1 → 取不高于回放 patch 的 15.8.0
        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 1, 1));

        assertEquals(base.resolve("data-15.8.0.0.13187581").resolve("live"), resolved);
    }

    @Test
    void samePatchPicksHighestBuild(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.8.0.0.13187581");
        dataDir(base, "data-15.8.0.0.13243917");

        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 0, 1));

        assertEquals(base.resolve("data-15.8.0.0.13243917").resolve("live"), resolved);
    }

    @Test
    void noSameMinorReturnsNull(@TempDir Path base) throws IOException {
        dataDir(base, "data-15.7.0.0.13015811");
        dataDir(base, "data-15.6.0.0.12830008");

        assertNull(GameDataCache.resolveGameDataDir(base, new Version(15, 8, 1, 13243917)));
    }

    @Test
    void ignoresDirsWithoutLiveAndNonDataNames(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("data-15.8.0.0.13187581"));   // 缺 live
        Files.createDirectories(base.resolve("not-data").resolve("live"));
        dataDir(base, "data-15.8.1.0.13243917");

        Path resolved = GameDataCache.resolveGameDataDir(base, new Version(15, 8, 1, 13243917));

        assertEquals(base.resolve("data-15.8.1.0.13243917").resolve("live"), resolved);
        assertNotEquals(base.resolve("data-15.8.0.0.13187581"), resolved);
    }

    @Test
    void nullBaseOrVersionReturnsNull() throws IOException {
        assertNull(GameDataCache.resolveGameDataDir(null, new Version(15, 8, 1, 1)));
        assertNull(GameDataCache.resolveGameDataDir(Path.of("."), null));
    }
}
