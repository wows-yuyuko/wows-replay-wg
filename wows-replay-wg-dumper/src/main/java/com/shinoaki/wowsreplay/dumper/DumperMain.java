package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * replay-dumper CLI 入口（对标 Rust {@code replay-dumper::main} 的 Single 模式子集）。
 *
 * <p>用法：</p>
 * <pre>{@code
 * java -cp ... com.wows.replay.dumper.DumperMain <REPLAY> -b <game-data-base>
 *   [-o out.json] [--minimap [--minimap-step N]] [--compress N]
 *   [--self-damage-stats] [--vehicle-events] [--battle-results]
 * }</pre>
 *
 * <p>多 rep 合并（--merge-mode/--alt-replays）暂未实现。</p>
 */
@Slf4j
public final class DumperMain {

    private DumperMain() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.err.println("错误: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Path replayPath = null;
        Path gameDataBase = null;
        Path outFile = null;
        boolean minimap = false;
        int minimapStep = 7;
        boolean selfDamageStats = false;
        boolean vehicleEvents = false;
        boolean battleResults = false;
        Integer compressLevel = null;

        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "-b", "--game-data-base" -> gameDataBase = Path.of(require(args, ++i, a));
                case "-o", "--out-file" -> outFile = Path.of(require(args, ++i, a));
                case "--minimap" -> minimap = true;
                case "--minimap-step" -> minimapStep = Integer.parseInt(require(args, ++i, a));
                case "--self-damage-stats" -> selfDamageStats = true;
                case "--vehicle-events" -> vehicleEvents = true;
                case "--battle-results" -> battleResults = true;
                case "--compress" -> compressLevel = Integer.parseInt(require(args, ++i, a));
                case "--merge-mode", "--alt-replays", "--cache-only", "--constants-file" ->
                    System.err.println("警告: 参数 " + a + " 未实现（多 rep 合并），忽略");
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("未知参数: " + a);
                    }
                    if (replayPath == null) replayPath = Path.of(a);
                }
            }
            i++;
        }

        if (replayPath == null || gameDataBase == null) {
            System.err.println("用法: DumperMain <REPLAY> -b <game-data-base> [-o out.json] [--minimap ...]");
            System.exit(2);
        }

        var replay = ReplayFile.fromFile(replayPath);
        var gameData = findGameData(gameDataBase, replay);
        if (gameData == null) {
            throw new IllegalArgumentException("在 " + gameDataBase + " 下未找到匹配版本 data-*/live（" + replay.version() + "）");
        }
        log.info("使用游戏数据: {}", gameData);

        var cache = GameDataCache.withMaxSize(4);
        var specProvider = cache.entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
        GameConstantsProvider constants = cache.constants(GameDataCache.VersionKey.from(gameData), gameData);

        var options = new ReplayDumper.Options(minimap, minimapStep, selfDamageStats, vehicleEvents, battleResults,
            compressLevel);
        var json = new ReplayDumper(specProvider, constants, gameData).dumpPrettyJson(replay, options);

        if (outFile != null) {
            Files.writeString(outFile, json);
            System.err.println("已写入 " + outFile);
        } else {
            System.out.println(json);
        }
    }

    private static String require(String[] args, int idx, String flag) {
        if (idx >= args.length) throw new IllegalArgumentException("参数 " + flag + " 需要值");
        return args[idx];
    }

    /** 在 base 下找 data-{major}.{minor}.{patch} 前缀目录下的 live（取 build 最高）。 */
    static Path findGameData(Path base, ReplayFile replay) throws IOException {
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

    /** 便于从其它入口复用的调试辅助。 */
    static String summarize(ReplayDumper.Options o) {
        return "ReplayDumper.Options(" + Arrays.asList(
            "minimap=" + o.minimap(), "step=" + o.minimapStep(),
            "selfDamageStats=" + o.selfDamageStats(), "vehicleEvents=" + o.vehicleEvents(),
            "battleResults=" + o.battleResults()) + ")";
    }
}
