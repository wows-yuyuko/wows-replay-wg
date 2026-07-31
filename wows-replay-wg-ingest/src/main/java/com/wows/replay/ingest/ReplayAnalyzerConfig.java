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
    boolean prettyPrint
) {
    public static final ReplayAnalyzerConfig DEFAULT = new ReplayAnalyzerConfig(
        false, 7, false, false, false, false
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

        public Builder minimap(boolean enabled) { minimap = enabled; return this; }
        public Builder minimap(boolean enabled, int step) { minimap = enabled; minimapStep = Math.max(1, step); return this; }
        public Builder vehicleEvents(boolean enabled) { vehicleEvents = enabled; return this; }
        public Builder selfDamageStats(boolean enabled) { selfDamageStats = enabled; return this; }
        public Builder decodePackets(boolean enabled) { decodePackets = enabled; return this; }
        public Builder prettyPrint(boolean enabled) { prettyPrint = enabled; return this; }

        public ReplayAnalyzerConfig build() {
            return new ReplayAnalyzerConfig(minimap, minimapStep, vehicleEvents, selfDamageStats, decodePackets, prettyPrint);
        }
    }
}
