package com.wows.dumper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI replay JSON dumper — mirrors {@code replay-dumper} from wows-toolkit.
 *
 * <h3>Usage</h3>
 * <pre>
 *   wows-dumper &lt;replay.wowsreplay&gt; -b &lt;game-data-base&gt; [options]
 * </pre>
 *
 * <h3>Options</h3>
 * <pre>
 *   -b, --game-data-base &lt;dir&gt;   base directory for data-{version}/live/
 *   -o, --out-file &lt;path&gt;         write output to file (default: stdout)
 *   -c, --constants-file &lt;path&gt;   path to constants.json for name resolution
 *   --minimap                       include minimap position timeline
 *   --minimap-step &lt;n&gt;             only emit minimap every N ticks (default: 7)
 *   --help                          print usage
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            System.exit(0);
            return;
        }

        // Parse positional: first non-flag arg is replay path
        Path replayPath = null;
        Path gameDataBase = null;
        Path outFile = null;
        Path constantsFile = null;
        boolean minimap = false;
        int minimapStep = 7;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-b", "--game-data-base" -> gameDataBase = Path.of(args[++i]);
                case "-o", "--out-file"        -> outFile = Path.of(args[++i]);
                case "-c", "--constants-file"  -> constantsFile = Path.of(args[++i]);
                case "--minimap"               -> minimap = true;
                case "--minimap-step"          -> minimapStep = Integer.parseInt(args[++i]);
                default -> {
                    if (!args[i].startsWith("-") && replayPath == null) {
                        replayPath = Path.of(args[i]);
                    }
                }
            }
            i++;
        }

        if (replayPath == null) {
            System.err.println("Error: missing replay file argument");
            printUsage();
            System.exit(1);
            return;
        }
        if (gameDataBase == null) {
            System.err.println("Error: missing -b/--game-data-base");
            printUsage();
            System.exit(1);
            return;
        }

        try {
            var pipeline = new DumperPipeline(gameDataBase);
            var options = new DumperPipeline.Options(minimap, minimapStep, constantsFile, null);
            String json = pipeline.dump(replayPath, options);

            if (outFile != null) {
                Files.writeString(outFile, json);
                System.err.println("Wrote " + outFile);
            } else {
                System.out.println(json);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (var a : args) if (a.equals(flag)) return true;
        return false;
    }

    private static void printUsage() {
        System.out.println("""
            wows-dumper — Extract WoWs replay data as JSON

            Usage:
              wows-dumper <replay.wowsreplay> -b <game-data-base> [options]

            Options:
              -b, --game-data-base <dir>   base directory for data-{version}/live/
              -o, --out-file <path>         write output to file (default: stdout)
              -c, --constants-file <path>   path to constants.json for name resolution
              --minimap                     include minimap position timeline
              --minimap-step <n>            only emit minimap every N ticks (default: 7)
              --help                        print this message
            """);
    }
}
