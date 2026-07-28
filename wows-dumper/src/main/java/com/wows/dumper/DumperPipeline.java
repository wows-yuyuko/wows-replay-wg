package com.wows.dumper;

import com.wows.replay.analyzer.BattleReport;
import com.wows.replay.analyzer.ReplayAnalyzer;
import com.wows.replay.analyzer.ReplayAnalyzerConfig;
import com.wows.replay.core.GameDataCache;
import com.wows.replay.core.JsonConstantsProvider;
import com.wows.replay.core.JsonMapper;
import com.wows.replay.core.ReplayException;
import com.wows.replay.core.ReplayFile;
import com.wows.replay.spec.types.Version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 回放 → JSON 管线，对标 Rust replay-dumper 的输出格式。
 */
public final class DumperPipeline {

    private final Path gameDataBase;

    /** 全局 LRU 缓存，跨多次 dump 共享已加载数据。 */
    private static final GameDataCache CACHE = GameDataCache.withMaxSize(4);

    public DumperPipeline(Path gameDataBase) { this.gameDataBase = gameDataBase; }

    /** 解析选项，对标 replay-dumper 的 ParseOptions。 */
    public record Options(
        boolean minimap, int minimapStep, Path constantsFile, Path gameParamFile
    ) {
        public static final Options DEFAULT = new Options(false, 7, null, null);
    }

    /** 从文件 dump。 */
    public String dump(Path replayPath, Options options) throws IOException, ReplayException {
        return dump(ReplayFile.fromFile(replayPath), options);
    }

    /** 从字节数组 dump。 */
    public String dump(byte[] replayBytes, Options options) throws ReplayException {
        return dump(ReplayFile.fromBytes(replayBytes), options);
    }

    private String dump(ReplayFile replay, Options options) {
        Path gameData = findGameData(replay);

        // 加载 constants.json（显式路径优先于缓存）
        JsonConstantsProvider constants = null;
        if (options.constantsFile != null && Files.exists(options.constantsFile)) {
            constants = JsonConstantsProvider.fromFile(options.constantsFile);
        } else if (gameData != null) {
            var ver = GameDataCache.VersionKey.from(gameData);
            constants = CACHE.constants(ver, gameData);
        }

        var analyzerBuilder = ReplayAnalyzer.builder()
            .config(ReplayAnalyzerConfig.builder()
                .minimap(options.minimap, options.minimapStep).build());
        if (constants != null) analyzerBuilder.constantsProvider(constants);
        var analyzer = analyzerBuilder.build();

        var report = analyzer.buildReport(replay);
        return buildJson(replay, report);
    }

    // ── 游戏数据目录发现 ────────────────────────────────────────────────────

    /** 查找与回放版本匹配的 data-{version}/live/ 目录。 */
    public Path findGameData(ReplayFile replay) {
        var ver = replay.meta().clientVersionFromExe().replace(',', '.');
        var version = Version.fromClientExe(replay.meta().clientVersionFromExe());

        // 精确匹配
        var exact = gameDataBase.resolve("data-" + ver).resolve("live");
        if (Files.exists(exact)) return exact;

        // 尝试 data-major.minor.patch.0build/live
        int lastDot = ver.lastIndexOf('.');
        if (lastDot > 0) {
            var altVer = ver.substring(0, lastDot) + ".0" + ver.substring(lastDot);
            var alt = gameDataBase.resolve("data-" + altVer).resolve("live");
            if (Files.exists(alt)) return alt;
        }

        // 模糊匹配：data-major.minor.patch.*/live，取最高 build
        var prefix = "data-" + version.toPath() + ".";
        Path best = null;
        long bestBuild = -1;
        try (var entries = Files.list(gameDataBase)) {
            for (var entry : entries.toList()) {
                var name = entry.getFileName().toString();
                if (!name.startsWith(prefix)) continue;
                var live = entry.resolve("live");
                if (!Files.exists(live)) continue;
                try {
                    long build = Long.parseLong(name.substring(prefix.length()));
                    if (build > bestBuild) { bestBuild = build; best = live; }
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return best;
    }

    // ── JSON 构建 ────────────────────────────────────────────────────────────

    private String buildJson(ReplayFile replay, BattleReport report) {
        var root = JsonMapper.createObject();
        var meta = replay.meta();

        root.put("arena_id", meta.mapId());
        root.put("date_time", meta.dateTime());
        root.put("version", meta.clientVersionFromExe().replace(',', '.'));
        root.put("map_id", meta.mapId());
        root.put("map_name", meta.mapDisplayName() != null ? meta.mapDisplayName() : meta.mapName());
        root.put("game_mode", meta.gameMode());
        root.put("game_type", meta.gameType());
        root.put("match_group", meta.matchGroup());

        root.set("meta", JsonMapper.toTree(report.meta()));
        root.set("summary", JsonMapper.toTree(report.summary()));
        root.set("packets", JsonMapper.toTree(report.packets()));
        if (report.entities() != null) root.set("entities", JsonMapper.toTree(report.entities()));
        if (report.resolvedVehicles() != null) root.set("vehicles_resolved", JsonMapper.toTree(report.resolvedVehicles()));
        if (report.minimap() != null) root.set("minimap", JsonMapper.toTree(report.minimap()));

        return JsonMapper.toJson(root);
    }
}
