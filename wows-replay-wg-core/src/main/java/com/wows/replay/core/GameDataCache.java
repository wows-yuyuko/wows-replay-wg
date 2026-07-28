package com.wows.replay.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局 LRU 缓存，按版本缓存已加载的游戏数据文件。
 *
 * <p>支持多版本并存，超过最大容量时淘汰最久未使用的条目。</p>
 */
public final class GameDataCache {

    private final int maxSize;
    private final Map<VersionKey, Object> store;

    private GameDataCache(int maxSize) {
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<VersionKey, Object> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** 创建最多缓存 {@code maxSize} 个版本的缓存。 */
    public static GameDataCache withMaxSize(int maxSize) {
        return new GameDataCache(maxSize);
    }

    /** 获取（或加载并缓存）指定版本的 constants.json。 */
    public JsonConstantsProvider constants(VersionKey version, Path gameDataDir) {
        var key = version.subKey("constants");
        return (JsonConstantsProvider) store.computeIfAbsent(key, _ -> {
            var path = gameDataDir.resolve("constants.json");
            if (!Files.exists(path)) return null;
            return JsonConstantsProvider.fromFile(path);
        });
    }

    /** 版本标识，按 major.minor.patch 分组，忽略 build 号。 */
    public record VersionKey(int major, int minor, int patch, String subKey) {

        /** 从目录名（如 "data-15.6.0.0.12830008"）提取。 */
        public static VersionKey from(Path gameDataDir) {
            var name = gameDataDir.getFileName().toString();
            return fromDirName(name);
        }

        /** 从 "data-M.m.p.b" 格式解析。 */
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

        /** 同版本号下不同子类型。 */
        public VersionKey subKey(String subKey) {
            return new VersionKey(major, minor, patch, subKey);
        }
    }
}
