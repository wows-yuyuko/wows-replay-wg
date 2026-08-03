package com.wows.replay.ingest;

/**
 * Configuration for {@link ReplayAnalyzer}.
 */
public record ReplayAnalyzerConfig(
    /** Enable minimap position timeline extraction */
    boolean minimap,

    /** Minimap frame step (1 = every tick, 5 = every 5th tick) */
    int minimapStep,

    /** Include per-vehicle consumable and kill event timelines */
    boolean vehicleEvents,

    /** Include server-authoritative per-weapon damage totals */
    boolean selfDamageStats,

    /** Enable packet-level decoding (requires EntitySpecProvider) */
    boolean decodePackets,

    /** Pretty-print the JSON output */
    boolean prettyPrint,

    /** 版本门禁（§12.4.1）：期望的 build（clientVersionFromExe 第 4 段），非空时校验。
     *  不匹配抛 {@link com.wows.replay.ReplayVersionMismatchException}。 */
    String expectedBuild
) {
    public static final ReplayAnalyzerConfig DEFAULT = new ReplayAnalyzerConfig(
        false, 7, false, false, false, false, null
    );

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean minimap;
        private int minimapStep = 7;
        private boolean vehicleEvents;
        private boolean selfDamageStats;
        private boolean decodePackets;
        private boolean prettyPrint;
        private String expectedBuild;

        public Builder minimap(boolean enabled) { minimap = enabled; return this; }
        public Builder minimap(boolean enabled, int step) { minimap = enabled; minimapStep = Math.max(1, step); return this; }
        public Builder vehicleEvents(boolean enabled) { vehicleEvents = enabled; return this; }
        public Builder selfDamageStats(boolean enabled) { selfDamageStats = enabled; return this; }
        public Builder decodePackets(boolean enabled) { decodePackets = enabled; return this; }
        public Builder prettyPrint(boolean enabled) { prettyPrint = enabled; return this; }
        /** 版本门禁：期望 build（clientVersionFromExe 第 4 段）。 */
        public Builder expectedBuild(String build) { expectedBuild = build; return this; }

        public ReplayAnalyzerConfig build() {
            return new ReplayAnalyzerConfig(minimap, minimapStep, vehicleEvents, selfDamageStats, decodePackets, prettyPrint, expectedBuild);
        }
    }
}
