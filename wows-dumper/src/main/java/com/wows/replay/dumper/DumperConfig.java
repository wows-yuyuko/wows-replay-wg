package com.wows.replay.dumper;

import java.nio.file.Path;

/**
 * Dumper 选项，对标 Rust {@code replay-dumper} 的 {@code ParseOptions}。
 */
public record DumperConfig(
    boolean minimap,
    int minimapStep,
    boolean vehicleEvents,
    boolean selfDamageStats,
    Path constantsFile,
    Path outFile,
    boolean prettyPrint
) {
    public static final DumperConfig DEFAULT = new DumperConfig(false, 7, false, false, null, null, false);

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean minimap;
        private int minimapStep = 7;
        private boolean vehicleEvents;
        private boolean selfDamageStats;
        private Path constantsFile;
        private Path outFile;
        private boolean prettyPrint;

        public Builder minimap(boolean enabled) { minimap = enabled; return this; }
        public Builder minimapStep(int step) { minimapStep = Math.max(1, step); return this; }
        public Builder vehicleEvents(boolean enabled) { vehicleEvents = enabled; return this; }
        public Builder selfDamageStats(boolean enabled) { selfDamageStats = enabled; return this; }
        public Builder constantsFile(Path p) { constantsFile = p; return this; }
        public Builder outFile(Path p) { outFile = p; return this; }
        public Builder prettyPrint(boolean enabled) { prettyPrint = enabled; return this; }

        public DumperConfig build() {
            return new DumperConfig(minimap, minimapStep, vehicleEvents, selfDamageStats, constantsFile, outFile, prettyPrint);
        }
    }
}
