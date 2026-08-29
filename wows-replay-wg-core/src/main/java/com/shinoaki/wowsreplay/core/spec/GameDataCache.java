package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.JsonConstantsProvider;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.data.WowsInfo;
import com.shinoaki.wowsreplay.core.model.Version;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 LRU 缓存，按版本缓存已加载的游戏数据。
 *
 * <p>支持多版本并存，超过最大容量时淘汰最久未使用的条目。</p>
 */
@Slf4j
public final class GameDataCache {

    private final int maxSize;
    /** 版本化数据缓存：一个版本一份 {@link GameDataEntry}（constants / 实体规范 / wowsinfo 整体加载）。 */
    private final Map<VersionKey, GameDataEntry> store;

    private GameDataCache(int maxSize) {
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<VersionKey, GameDataEntry> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** 创建最多缓存 {@code maxSize} 个版本的缓存。 */
    public static GameDataCache withMaxSize(int maxSize) {
        return new GameDataCache(maxSize);
    }

    /**
     * 获取（或加载并缓存）replay 对应版本的整份游戏数据。
     *
     * <p>唯一缓存入口：一次调用同时加载 constants.json、实体规范、wowsinfo.json，
     * 同一 major.minor.patch 的所有 build 共享同一份；未匹配到数据目录返回 {@link GameDataEntry#EMPTY}。</p>
     */
    public GameDataEntry gameData(ReplayFile replay) {
        var gd = resolve(replay);
        if (gd == null) {
            log.warn("未找到匹配版本的游戏数据（base={}），返回空条目", replay.gameDataBase());
            return GameDataEntry.EMPTY;
        }
        return store.computeIfAbsent(gd.version, _ -> load(gd.dir));
    }

    /**
     * 在 base 下按 replay 版本定位 {@code data-M.m.p.b/live} 目录（同版本取 build 最高）。
     *
     * @return live 目录；base 为 null 或未匹配到返回 null
     */
    public static Path resolveGameDataDir(ReplayFile replay) throws IOException {
        Path base = replay.gameDataBase();
        if (base == null) return null;
        var v = replay.version();
        String prefix = "data-" + v.major() + "." + v.minor() + "." + v.patch() + ".";
        Path best = null;
        long bestBuild = -1;
        try (var entries = Files.list(base)) {
            for (var dir : entries.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                if (!name.startsWith(prefix)) continue;
                String buildStr = name.substring(prefix.length()).split("\\.")[0];
                long build;
                try {
                    build = Long.parseLong(buildStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                var live = dir.resolve("live");
                if (Files.isDirectory(live) && build > bestBuild) {
                    best = live;
                    bestBuild = build;
                }
            }
        }
        return best;
    }

    /** 整版本加载：constants / 实体规范 / wowsinfo 一次性解析（缓存键已按版本去重，只执行一次）。 */
    private GameDataEntry load(Path gameDataDir) {
        var specs = loadEntitySpecs(gameDataDir);
        return new GameDataEntry(loadConstants(gameDataDir), _ -> specs, loadWowsInfo(gameDataDir));
    }

    private JsonConstantsProvider loadConstants(Path gameDataDir) {
        var path = gameDataDir.resolve("constants.json");
        if (!Files.exists(path)) {
            log.warn("{} 缺少 constants.json，返回空常量实现", gameDataDir);
            return GameDataEntry.EMPTY.constants();
        }
        return JsonConstantsProvider.fromFile(path);
    }

    private List<EntitySpec> loadEntitySpecs(Path gameDataDir) {
        // 验证 .def 文件存在性
        var entitiesXml = gameDataDir.resolve("scripts/entities.xml");
        var aliasXml = gameDataDir.resolve("scripts/entity_defs/alias.xml");
        if (!Files.exists(entitiesXml) || !Files.exists(aliasXml)) {
            log.warn("{} 中缺少 scripts/entities.xml 或 scripts/entity_defs/alias.xml", gameDataDir);
            return List.of();
        }
        // version 参数对解析无影响（loader 已绑定该目录），传占位版本。
        return new EntityRegistry(path -> {
            var file = gameDataDir.resolve(path);
            if (!Files.exists(file)) throw new IOException("def file not found: " + path);
            return Files.readAllBytes(file);
        }).loadSpecs(new Version(0, 0, 0, 0));
    }

    private WowsInfo loadWowsInfo(Path liveDir) {
        var path = liveDir.resolve("app/data/wowsinfo.json");
        if (!Files.exists(path)) {
            log.warn("{} 缺少 wowsinfo.json，返回空映射", path);
            return WowsInfo.EMPTY;
        }
        try {
            return WowsInfo.fromJson(Files.readString(path));
        } catch (Exception e) {
            log.warn("解析 wowsinfo.json 失败 {}: {}", path, e.toString());
            return WowsInfo.EMPTY;
        }
    }

    /** 解析 replay → (版本键, live 目录)；未匹配或 IO 异常返回 null。 */
    private static ResolvedDir resolve(ReplayFile replay) {
        Path dir;
        try {
            dir = resolveGameDataDir(replay);
        } catch (IOException e) {
            log.warn("定位游戏数据目录失败（base={}）: {}", replay.gameDataBase(), e.toString());
            return null;
        }
        if (dir == null) return null;
        // 版本键取自 data-* 目录名（live 的父目录），非 live 本身。
        var vk = VersionKey.from(dir.getParent());
        return new ResolvedDir(vk, dir);
    }

    private record ResolvedDir(VersionKey version, Path dir) {
    }



    /** 版本标识，按 major.minor.patch 分组，忽略 build 号。 */
    public record VersionKey(int major, int minor, int patch) {

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
            } catch (NumberFormatException ignored) {
            }
            return new VersionKey(major, minor, patch);
        }
    }
}
