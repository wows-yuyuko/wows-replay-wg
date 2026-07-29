package com.wows.dumper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 回放 JSON 导出命令行工具，对标 wows-toolkit 的 replay-dumper。
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            System.exit(0);
            return;
        }

        Path replayPath = null;
        Path gameDataBase = null;
        Path outFile = null;
        Path constantsFile = null;
        boolean minimap = false;
        int minimapStep = 7;
        boolean vehicleEvents = false;
        boolean selfDamageStats = false;
        Integer compress = null;
        boolean battleResults = false;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-b", "--game-data-base"   -> gameDataBase = Path.of(args[++i]);
                case "-o", "--out-file"         -> outFile = Path.of(args[++i]);
                case "-c", "--constants-file"   -> constantsFile = Path.of(args[++i]);
                case "--minimap"                -> minimap = true;
                case "--minimap-step"           -> minimapStep = parseInt(args[++i], 7);
                case "--vehicle-events"         -> vehicleEvents = true;
                case "--self-damage-stats"      -> selfDamageStats = true;
                case "--compress"               -> compress = parseInt(args[++i], 6);
                case "--battle-results"         -> battleResults = true;
                default -> {
                    if (!args[i].startsWith("-") && replayPath == null) replayPath = Path.of(args[i]);
                }
            }
            i++;
        }

        if (replayPath == null) { System.err.println("错误：缺少回放文件参数"); printUsage(); System.exit(1); return; }
        if (gameDataBase == null) { System.err.println("错误：缺少 -b/--game-data-base"); printUsage(); System.exit(1); return; }

        try {
            var pipeline = new DumperPipeline(gameDataBase);
            var options = new DumperPipeline.Options(
                minimap, minimapStep, constantsFile, null,
                vehicleEvents, selfDamageStats, compress, battleResults);
            String json = pipeline.dump(replayPath, options);

            if (outFile != null) {
                Files.writeString(outFile, json);
                System.err.println("已写入 " + outFile);
            } else {
                System.out.println(json);
            }
        } catch (Exception e) {
            System.err.println("错误：" + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (var a : args) if (a.equals(flag)) return true;
        return false;
    }

    private static int parseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static void printUsage() {
        System.out.println("""
            wows-dumper — 将 WoWs 回放数据导出为 JSON

            用法:
              wows-dumper <replay.wowsreplay> -b <game-data-base> [选项]

            选项:
              -b, --game-data-base <dir>   data-{version}/live/ 的父目录
              -o, --out-file <path>        输出文件（默认 stdout）
              -c, --constants-file <path>  constants.json 路径（可选）
              --minimap                     包含小地图位置时间线
              --minimap-step <n>           小地图采样间隔（默认 7）
              --vehicle-events              包含每玩家消耗品 / 击杀事件时间线
              --self-damage-stats           包含自身伤害统计
              --compress <level>           小地图字段 Brotli 压缩级别（0-11）
              --battle-results             包含原始 BattleResults JSON
              --help                       打印帮助

            注意:
              --merge-mode / --alt-replays  尚未实现（TODO）
            """);
    }
}
