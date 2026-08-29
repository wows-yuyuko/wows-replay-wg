package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * replay-dumper CLI 入口（对标 Rust {@code replay-dumper::main} 的 Single 模式子集）。
 *
 * <p>用法：</p>
 * <pre>{@code
 * java -cp ... com.wows.replay.dumper.DumperMain <REPLAY> -b <game-data-base>
 *   [-o out.json] [--minimap] [--compress N]
 *   [--self-damage-stats] [--vehicle-events] [--battle-results]
 *   [--ship-config-dump shipconfig.json]   ← 只输出所有玩家 shipConfig 原始 blob（逆向诊断用）
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
        boolean selfDamageStats = false;
        int compressLevel = 0;
        Path shipConfigDump = null;

        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "-b", "--game-data-base" -> gameDataBase = Path.of(require(args, ++i, a));
                case "-o", "--out-file" -> outFile = Path.of(require(args, ++i, a));
                case "--minimap" -> minimap = true;
                case "--self-damage-stats" -> selfDamageStats = true;
                case "--compress" -> compressLevel = Integer.parseInt(require(args, ++i, a));
                case "--ship-config-dump" -> shipConfigDump = Path.of(require(args, ++i, a));
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

        var replay = ReplayFile.fromFile(replayPath, gameDataBase);
        var gameData = GameDataCache.resolveGameDataDir(replay);
        if (gameData == null) {
            throw new IllegalArgumentException("在 " + gameDataBase + " 下未找到匹配版本 data-*/live（" + replay.version() + "）");
        }
        log.info("使用游戏数据: {}", gameData);

        var replayDumper = new ReplayDumper(replay, new ReplayDumper.Options(minimap, selfDamageStats, compressLevel));

        // 逆向诊断模式：只输出所有玩家 shipConfig 原始 blob（含未识别尾部），不跑主装配
        if (shipConfigDump != null) {
            var scJson = JsonMapper.toPrettyJson(replayDumper.dumpShipConfigs());
            Files.writeString(shipConfigDump, scJson);
            System.err.println("已写入 shipConfig dump: " + shipConfigDump);
            return;
        }

        var json = replayDumper.dumpPrettyJson();

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
}
