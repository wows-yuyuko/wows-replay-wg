package com.wows.replay.analyzer;

import com.wows.replay.core.PacketTypeId;
import com.wows.replay.core.RawPacket;
import com.wows.replay.core.ReplayException;
import com.wows.replay.core.ReplayFile;
import com.wows.replay.packets.*;
import com.wows.replay.spec.spi.EntitySpecProvider;
import com.wows.replay.spec.spi.GameConstantsProvider;
import com.wows.replay.spec.spi.GameParamProvider;
import com.wows.replay.spec.types.GameClock;
import com.wows.replay.core.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Main entry point for replay analysis.
 *
 * <p>Processes a {@link ReplayFile} end-to-end, collecting statistics,
 * decoding packets (optionally with entity specs), and producing a
 * {@link BattleReport} that can be serialized to JSON.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ReplayFile replay = ReplayFile.fromFile(Path.of("replay.wowsreplay"));
 *
 * // Quick: no packet decoding (fast, no game data needed)
 * String json = ReplayAnalyzer.quick(replay);
 *
 * // Full: with entity specs and game constants
 * ReplayAnalyzer analyzer = ReplayAnalyzer.builder()
 *     .specProvider(mySpecProvider)
 *     .constantsProvider(myConstantsProvider)
 *     .config(ReplayAnalyzerConfig.builder()
 *         .minimap(true)
 *         .decodePackets(true)
 *         .build())
 *     .build();
 * String json = analyzer.analyze(replay);
 * }</pre>
 */
public final class ReplayAnalyzer {

    private final EntitySpecProvider specProvider;
    private final GameParamProvider paramProvider;
    private final GameConstantsProvider constantsProvider;
    private final ReplayAnalyzerConfig config;

    private ReplayAnalyzer(EntitySpecProvider specProvider,
                           GameParamProvider paramProvider,
                           GameConstantsProvider constantsProvider,
                           ReplayAnalyzerConfig config) {
        this.specProvider = specProvider;
        this.paramProvider = paramProvider;
        this.constantsProvider = constantsProvider;
        this.config = config;
    }

    // ── Quick API ───────────────────────────────────────────────────────────

    /**
     * Quick analysis without any providers or packet decoding.
     * Fast and works without game data. Provides metadata + packet statistics.
     */
    public static String quick(ReplayFile replay) throws ReplayException {
        if (replay == null) throw new ReplayException("replay must not be null");
        var analyzer = new ReplayAnalyzer(null, null, null, ReplayAnalyzerConfig.DEFAULT);
        return analyzer.analyze(replay);
    }

    /**
     * Quick analysis from a file path.
     */
    public static String quick(Path replayPath) throws ReplayException, IOException {
        return quick(ReplayFile.fromFile(replayPath));
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private EntitySpecProvider specProvider;
        private GameParamProvider paramProvider;
        private GameConstantsProvider constantsProvider;
        private ReplayAnalyzerConfig config = ReplayAnalyzerConfig.DEFAULT;

        public Builder specProvider(EntitySpecProvider p) {
            specProvider = p;
            return this;
        }

        public Builder paramProvider(GameParamProvider p) {
            paramProvider = p;
            return this;
        }

        public Builder constantsProvider(GameConstantsProvider p) {
            constantsProvider = p;
            return this;
        }

        public Builder config(ReplayAnalyzerConfig c) {
            config = c;
            return this;
        }

        public ReplayAnalyzer build() {
            return new ReplayAnalyzer(specProvider, paramProvider, constantsProvider, config);
        }
    }

    // ── Analysis ────────────────────────────────────────────────────────────

    /**
     * Analyze a replay and return the JSON report.
     */
    public String analyze(ReplayFile replay) throws ReplayException {
        var report = buildReport(replay);
        return config.prettyPrint() ? JsonMapper.toPrettyJson(report) : JsonMapper.toJson(report);
    }

    /**
     * Analyze a replay and return the structured {@link BattleReport}.
     */
    public BattleReport buildReport(ReplayFile replay) {
        // Packet parser (optional, depends on specProvider)
        var parser = (specProvider != null && config.decodePackets())
                ? new PacketParser(specProvider, replay.version())
                : new PacketParser();

        // Statistics accumulators
        var packetCounts = new LinkedHashMap<String, Integer>();
        int unknownCount = 0;
        int invalidCount = 0;
        int positionCount = 0;
        int entityCreateCount = 0;
        int entityMethodCount = 0;

        var entityEvents = new ArrayList<BattleReport.EntityEvent>();
        var chatMessages = new ArrayList<BattleReport.ChatMessage>();
        var minimapFrames = new ArrayList<BattleReport.MinimapFrame>();
        int minimapTickCounter = 0;
        int minimapStep = config.minimapStep();

        GameClock battleStart = replay.battleStartClock();
        GameClock lastClock = GameClock.ZERO;

        // Walk all packets
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            lastClock = raw.clock();

            // Packet type statistics
            String typeName = raw.packetType() != null ? raw.packetType().displayName() : "unknown";
            packetCounts.merge(typeName, 1, Integer::sum);
            if (raw.isUnknown()) unknownCount++;

            // Track specific packet types for summary
            if (raw.packetType() != null) {
                switch (raw.packetType()) {
                    case POSITION -> positionCount++;
                    case ENTITY_CREATE -> entityCreateCount++;
                    case ENTITY_METHOD -> entityMethodCount++;
                }
            }

            // Decode packet if configured
            if (config.decodePackets()) {
                var packet = parser.parse(raw);
                Object payload = packet.payload();

                if (payload instanceof Packet.InvalidPayload) {
                    invalidCount++;
                }

                // Entity events
                if (payload instanceof EntityCreatePacket ecp) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "create", ecp.entityId().value(),
                            ecp.entityType(), ecp.vehicleId().value()
                    ));
                } else if (payload instanceof EntityEnterPacket eep) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "enter", eep.entityId().value(),
                            null, eep.vehicleId().value()
                    ));
                } else if (payload instanceof EntityLeavePacket elp) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "leave", elp.entityId().value(),
                            null, 0
                    ));
                }
            }

            // Minimap extraction
            if (config.minimap() && minimapTickCounter % minimapStep == 0) {
                if (raw.packetType() == PacketTypeId.POSITION && raw.clock().seconds() >= battleStart.seconds()) {
                    extractMinimapFrame(raw, minimapFrames);
                }
            }
            minimapTickCounter++;
        }

        // Resolve vehicle names via GameParamProvider
        var resolvedVehicles = resolveVehicleNames(replay);

        // Build report sections
        return new BattleReport(
                BattleReport.MetaSection.from(replay.meta()),
                new BattleReport.SummarySection(
                        packetCounts.values().stream().mapToInt(Integer::intValue).sum(),
                        battleStart.seconds(), lastClock.seconds(),
                        positionCount, entityCreateCount, entityMethodCount
                ),
                new BattleReport.PacketsSection(packetCounts, unknownCount, invalidCount),
                entityEvents.isEmpty() ? null : entityEvents,
                null, // vehicle events (placeholder)
                chatMessages.isEmpty() ? null : chatMessages,
                null, // damage section (placeholder)
                minimapFrames.isEmpty() ? null : new BattleReport.MinimapSection(minimapStep, minimapFrames),
                resolvedVehicles.isEmpty() ? null : resolvedVehicles
        );
    }

    // ── Vehicle name resolution ──────────────────────────────────────────────

    /**
     * Resolve vehicle IDs from metadata to human-readable names using
     * the configured {@link GameParamProvider}.
     * Mirrors replay-dumper's {@code param_names} + {@code resolve_ids}.
     */
    private List<BattleReport.ResolvedVehicle> resolveVehicleNames(ReplayFile replay) {
        var meta = replay.meta();
        var vehicles = meta.vehicles();
        if (vehicles == null || vehicles.isEmpty()) return List.of();

        var names = paramProvider != null
            ? paramProvider.paramNames()
            : java.util.Collections.<Long, String>emptyMap();

        return vehicles.stream()
            .map(v -> {
                var id = v.shipId().value();
                return new BattleReport.ResolvedVehicle(
                    id,
                    names.getOrDefault(id, String.valueOf(id)),
                    paramProvider != null
                        ? paramProvider.paramIndexById(v.shipId())
                            .map(Object::toString).orElse(String.valueOf(id))
                        : String.valueOf(id),
                    v.relation(),
                    v.name());
            })
            .toList();
    }

    // ── Minimap ─────────────────────────────────────────────────────────────

    private void extractMinimapFrame(RawPacket raw, List<BattleReport.MinimapFrame> frames) {
        try {
            var buf = java.nio.ByteBuffer.wrap(raw.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 41) return; // Minimal Position packet: entityId+spaceId+Vec3+Vec3+Rot3+bool

            int entityId = buf.getInt();
            // Skip spaceId (4 bytes)
            buf.getInt();
            float x = buf.getFloat();
            float y = buf.getFloat();
            buf.getFloat(); // z
            // Skip direction Vec3
            buf.getFloat();
            buf.getFloat();
            buf.getFloat();
            float yaw = buf.getFloat(); // rotation.yaw

            var entity = new BattleReport.MinimapEntity(entityId, x, y, yaw, 0);
            frames.add(new BattleReport.MinimapFrame(raw.clock().seconds(), List.of(entity)));
        } catch (Exception ignored) {
            // Skip malformed position packets in minimap extraction
        }
    }
}
