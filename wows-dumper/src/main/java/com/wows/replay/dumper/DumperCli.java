package com.wows.replay.dumper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * wows-dumper CLI — 对标 Rust {@code replay-dumper-cli}。
 *
 * <pre>
 *   wows-dumper &lt;REPLAY&gt; -b &lt;GAME_DATA_BASE&gt; [-o OUT] [--minimap] [--minimap-step N]
 *               [--battle-results] [--self-damage-stats] [--vehicle-events] [--pretty]
 * </pre>
 */
public final class DumperCli {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: wows-dumper <REPLAY> -b <GAME_DATA_BASE> [-o OUT] [--minimap] [--minimap-step N] [--pretty]");
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Path replay = null;
        Path gameDataBase = null;
        boolean minimap = false;
        int minimapStep = 7;
        boolean vehicleEvents = false;
        boolean selfDamageStats = false;
        boolean pretty = false;
        List<Path> altReplays = new ArrayList<>();
        Path constantsFile = null;
        Path outFile = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-b", "--game-data-base" -> gameDataBase = Path.of(args[++i]);
                case "-o", "--out-file" -> outFile = Path.of(args[++i]);
                case "-c", "--constants-file" -> constantsFile = Path.of(args[++i]);
                case "--minimap" -> minimap = true;
                case "--minimap-step" -> minimapStep = Integer.parseInt(args[++i]);
                case "--vehicle-events" -> vehicleEvents = true;
                case "--self-damage-stats" -> selfDamageStats = true;
                case "--pretty" -> pretty = true;
                case "--alt-replays" -> altReplays.add(Path.of(args[++i]));
                default -> {
                    if (a.startsWith("-")) throw new IllegalArgumentException("unknown option: " + a);
                    if (replay == null) replay = Path.of(a);
                    else altReplays.add(Path.of(a));
                }
            }
        }
        if (replay == null || gameDataBase == null) {
            throw new IllegalArgumentException("需要 <REPLAY> 与 -b <GAME_DATA_BASE>");
        }

        var cfg = DumperConfig.builder()
            .minimap(minimap)
            .minimapStep(minimapStep)
            .vehicleEvents(vehicleEvents)
            .selfDamageStats(selfDamageStats)
            .constantsFile(constantsFile)
            .outFile(outFile)
            .prettyPrint(pretty)
            .build();

        String json = Dumper.dump(replay, gameDataBase, cfg);
        if (outFile != null) {
            Files.writeString(outFile, json);
            System.err.println("Wrote " + outFile);
        } else {
            System.out.println(json);
        }
    }
}
