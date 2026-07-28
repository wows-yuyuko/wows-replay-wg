package com.wows.replay.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global LRU cache for loaded game data files, keyed by version.
 *
 * <p>Supports multiple game versions concurrently and evicts the
 * least-recently-used entry when the cache exceeds its maximum size.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var cache = GameDataCache.withMaxSize(4);
 *
 * // Auto-loads and caches
 * var params = cache.wowsInfo(version, gameDataDir);
 * var constants = cache.constants(version, gameDataDir);
 *
 * // Pre-load a specific version
 * cache.preload(gameDataDir);
 * }</pre>
 */
public final class GameDataCache {

    private final int maxSize;
    private final Map<VersionKey, Object> store;

    private GameDataCache(int maxSize) {
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(maxSize, 0.75f, true) { // access-order
            @Override
            protected boolean removeEldestEntry(Map.Entry<VersionKey, Object> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** Create a cache that holds up to {@code maxSize} versions. */
    public static GameDataCache withMaxSize(int maxSize) {
        return new GameDataCache(maxSize);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Load (or retrieve cached) constants.json for a version. */
    public JsonConstantsProvider constants(VersionKey version, Path gameDataDir) {
        var key = version.subKey("constants");
        return (JsonConstantsProvider) store.computeIfAbsent(key, _ -> {
            var path = gameDataDir.resolve("constants.json");
            if (!Files.exists(path)) return null;
            return JsonConstantsProvider.fromFile(path);
        });
    }

    // ── Version key ──────────────────────────────────────────────────────────

    /**
     * Identifies a game version + data sub-type for cache lookup.
     * Equality is based on version triple (major.minor.patch) and sub-key,
     * ignoring the build number so minor patches share caches.
     */
    public record VersionKey(int major, int minor, int patch, String subKey) {

        /** Extract from a data directory name like "data-15.6.0.0.12830008". */
        public static VersionKey from(Path gameDataDir) {
            var name = gameDataDir.getFileName().toString();
            return fromDirName(name);
        }

        /** Parse from a "data-M.m.p.b" directory name. */
        public static VersionKey fromDirName(String dirName) {
            var parts = dirName.replace("data-", "").split("\\.");
            int major = 0, minor = 0, patch = 0;
            try {
                if (parts.length >= 1) major = Integer.parseInt(parts[0]);
                if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
                if (parts.length >= 3) patch = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {}
            return new VersionKey(major, minor, patch, "");
        }

        /** Same version triple with a different sub-key. */
        public VersionKey subKey(String subKey) {
            return new VersionKey(major, minor, patch, subKey);
        }
    }
}
