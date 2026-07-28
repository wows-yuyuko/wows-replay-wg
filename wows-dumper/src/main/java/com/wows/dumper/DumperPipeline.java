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
 * Replay → JSON pipeline, mirroring Rust replay-dumper's output format.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var pipeline = new DumperPipeline(Path.of("/data/wows"));
 * String json = pipeline.dump(Path.of("replay.wowsreplay"), options);
 * }</pre>
 */
public final class DumperPipeline {

    private final Path gameDataBase;

    /** Shared LRU cache for loaded game data across dumps. */
    private static final GameDataCache CACHE = GameDataCache.withMaxSize(4);

    public DumperPipeline(Path gameDataBase) {
        this.gameDataBase = gameDataBase;
    }

    /** Parse options mirroring replay-dumper's ParseOptions. */
    public record Options(
        boolean minimap,
        int minimapStep,
        Path constantsFile,
        Path gameParamFile
    ) {
        public static final Options DEFAULT = new Options(false, 7, null, null);
    }

    /** Dump a replay file to JSON string. */
    public String dump(Path replayPath, Options options) throws IOException, ReplayException {
        return dump(ReplayFile.fromFile(replayPath), options);
    }

    /** Dump replay bytes to JSON string. */
    public String dump(byte[] replayBytes, Options options) throws ReplayException {
        return dump(ReplayFile.fromBytes(replayBytes), options);
    }

    private String dump(ReplayFile replay, Options options) {
        // Find game data directory for this version
        Path gameData = findGameData(replay);

        // Load constants.json (explicit path overrides cache)
        JsonConstantsProvider constants = null;
        if (options.constantsFile != null && Files.exists(options.constantsFile)) {
            constants = JsonConstantsProvider.fromFile(options.constantsFile);
        } else if (gameData != null) {
            var ver = GameDataCache.VersionKey.from(gameData);
            constants = CACHE.constants(ver, gameData);
        }

        // Run the analyzer with optional providers
        var analyzerBuilder = ReplayAnalyzer.builder()
            .config(ReplayAnalyzerConfig.builder()
                .minimap(options.minimap, options.minimapStep)
                .build());
        if (constants != null) analyzerBuilder.constantsProvider(constants);
        var analyzer = analyzerBuilder.build();

        var report = analyzer.buildReport(replay);

        // Build JSON in replay-dumper format
        return buildJson(replay, report);
    }

    // ── Game data discovery ──────────────────────────────────────────────────

    /** Find the data-{version}/live/ directory matching the replay version. */
    public Path findGameData(ReplayFile replay) {
        var ver = replay.meta().clientVersionFromExe().replace(',', '.');
        var version = Version.fromClientExe(replay.meta().clientVersionFromExe());

        // Exact match: data-major.minor.patch.build/live
        var exact = gameDataBase.resolve("data-" + ver).resolve("live");
        if (Files.exists(exact)) return exact;

        // Try data-major.minor.patch.0build/live
        int lastDot = ver.lastIndexOf('.');
        if (lastDot > 0) {
            var altVer = ver.substring(0, lastDot) + ".0" + ver.substring(lastDot);
            var alt = gameDataBase.resolve("data-" + altVer).resolve("live");
            if (Files.exists(alt)) return alt;
        }

        // Fuzzy: data-major.minor.patch.*/live, pick highest build
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

    // ── JSON builder ─────────────────────────────────────────────────────────

    private String buildJson(ReplayFile replay, BattleReport report) {
        var root = JsonMapper.createObject();
        var meta = replay.meta();

        // Top-level fields matching replay-dumper output
        root.put("arena_id", meta.mapId());
        root.put("date_time", meta.dateTime());
        root.put("version", meta.clientVersionFromExe().replace(',', '.'));
        root.put("map_id", meta.mapId());
        root.put("map_name", meta.mapDisplayName() != null ? meta.mapDisplayName() : meta.mapName());
        root.put("game_mode", meta.gameMode());
        root.put("game_type", meta.gameType());
        root.put("match_group", meta.matchGroup());

        // Meta section (full metadata)
        root.set("meta", JsonMapper.toTree(report.meta()));

        // Summary
        root.set("summary", JsonMapper.toTree(report.summary()));

        // Packet stats
        root.set("packets", JsonMapper.toTree(report.packets()));

        // Entity events
        if (report.entities() != null) {
            root.set("entities", JsonMapper.toTree(report.entities()));
        }

        // Resolved vehicles
        if (report.resolvedVehicles() != null) {
            root.set("vehicles_resolved", JsonMapper.toTree(report.resolvedVehicles()));
        }

        // Minimap
        if (report.minimap() != null) {
            root.set("minimap", JsonMapper.toTree(report.minimap()));
        }

        // Serialize JObject builder to JSON string
        return JsonMapper.toJson(root);
    }
}
