package com.wows.replay.ingest;

import com.wows.replay.PacketTypeId;
import com.wows.replay.packet.RawPacket;
import com.wows.replay.ReplayException;
import com.wows.replay.ReplayFile;
import com.wows.replay.packet.*;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;
import com.wows.replay.model.GameClock;
import com.wows.replay.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 回放分析入口，对标 wows-toolkit 的 replay-dumper 管线。
 *
 * <p>遍历回放的所有数据包，解码、统计并生成 {@link BattleReport}。可选注入
 * {@link EntitySpecProvider}（实体属性解码）和 {@link GameConstantsProvider}
 * （常量名称解析）。</p>
 */
public final class ReplayAnalyzer {

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constantsProvider;
    private final ReplayAnalyzerConfig config;

    private ReplayAnalyzer(EntitySpecProvider specProvider,
                           GameConstantsProvider constantsProvider,
                           ReplayAnalyzerConfig config) {
        this.specProvider = specProvider;
        this.constantsProvider = constantsProvider;
        this.config = config;
    }

    // ── 快捷 API ─────────────────────────────────────────────────────────────

    /** 快速分析（无实体规范、无包解码），只输出元数据 + 包类型统计。 */
    public static String quick(ReplayFile replay) throws ReplayException {
        if (replay == null) throw new ReplayException("replay 不能为 null");
        var analyzer = new ReplayAnalyzer(null, null, ReplayAnalyzerConfig.DEFAULT);
        return analyzer.analyze(replay);
    }

    /** 从文件路径快速分析。 */
    public static String quick(Path replayPath) throws ReplayException, IOException {
        return quick(ReplayFile.fromFile(replayPath));
    }

    // ── 构建器 ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private EntitySpecProvider specProvider;
        private GameConstantsProvider constantsProvider;
        private ReplayAnalyzerConfig config = ReplayAnalyzerConfig.DEFAULT;

        public Builder specProvider(EntitySpecProvider p) { specProvider = p; return this; }
        public Builder constantsProvider(GameConstantsProvider p) { constantsProvider = p; return this; }
        public Builder config(ReplayAnalyzerConfig c) { config = c; return this; }
        public ReplayAnalyzer build() { return new ReplayAnalyzer(specProvider, constantsProvider, config); }
    }

    // ── 分析 ─────────────────────────────────────────────────────────────────

    /** 分析回放并返回 JSON 报告。 */
    public String analyze(ReplayFile replay) throws ReplayException {
        var report = buildReport(replay);
        return config.prettyPrint() ? JsonMapper.toPrettyJson(report) : JsonMapper.toJson(report);
    }

    /** 分析回放并返回结构化 {@link BattleReport}。 */
    public BattleReport buildReport(ReplayFile replay) {
        var parser = (specProvider != null && config.decodePackets())
                ? new Parser(specProvider, replay.version())
                : new Parser();

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

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            lastClock = raw.clock();

            String typeName = raw.packetType() != null ? raw.packetType().displayName() : "unknown";
            packetCounts.merge(typeName, 1, Integer::sum);
            if (raw.isUnknown()) unknownCount++;

            if (raw.packetType() != null) {
                switch (raw.packetType()) {
                    case POSITION -> positionCount++;
                    case ENTITY_CREATE -> entityCreateCount++;
                    case ENTITY_METHOD -> entityMethodCount++;
                }
            }

            if (config.decodePackets()) {
                var packet = parser.parse(raw);
                Object payload = packet.payload();
                if (payload instanceof Packet.InvalidPayload) invalidCount++;

                if (payload instanceof EntityCreatePacket ecp) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "create", ecp.entityId().value(),
                            ecp.entityType(), ecp.vehicleId().value()));
                } else if (payload instanceof EntityEnterPacket eep) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "enter", eep.entityId().value(),
                            null, eep.vehicleId().value()));
                } else if (payload instanceof EntityLeavePacket elp) {
                    entityEvents.add(new BattleReport.EntityEvent(
                            raw.clock().seconds(), "leave", elp.entityId().value(),
                            null, 0));
                }
            }

            if (config.minimap() && minimapTickCounter % minimapStep == 0) {
                if (raw.packetType() == PacketTypeId.POSITION && raw.clock().seconds() >= battleStart.seconds()) {
                    extractMinimapFrame(raw, minimapFrames);
                }
            }
            minimapTickCounter++;
        }

        var resolvedVehicles = resolveVehicleNames(replay);

        return new BattleReport(
                BattleReport.MetaSection.from(replay.meta()),
                new BattleReport.SummarySection(
                        packetCounts.values().stream().mapToInt(Integer::intValue).sum(),
                        battleStart.seconds(), lastClock.seconds(),
                        positionCount, entityCreateCount, entityMethodCount),
                new BattleReport.PacketsSection(packetCounts, unknownCount, invalidCount),
                entityEvents.isEmpty() ? null : entityEvents,
                null, chatMessages.isEmpty() ? null : chatMessages,
                null,
                minimapFrames.isEmpty() ? null : new BattleReport.MinimapSection(minimapStep, minimapFrames),
                resolvedVehicles.isEmpty() ? null : resolvedVehicles);
    }

    /** 从回放元数据中提取车辆列表。 */
    private List<BattleReport.ResolvedVehicle> resolveVehicleNames(ReplayFile replay) {
        var vehicles = replay.meta().vehicles();
        if (vehicles == null || vehicles.isEmpty()) return List.of();
        return vehicles.stream()
            .map(v -> new BattleReport.ResolvedVehicle(v.shipId().value(), v.relation(), v.name()))
            .toList();
    }

    // ── 小地图 ───────────────────────────────────────────────────────────────

    private void extractMinimapFrame(RawPacket raw, List<BattleReport.MinimapFrame> frames) {
        try {
            var buf = java.nio.ByteBuffer.wrap(raw.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 41) return;

            int entityId = buf.getInt();
            buf.getInt(); // spaceId
            float x = buf.getFloat();
            float y = buf.getFloat();
            buf.getFloat(); // z
            buf.getFloat(); buf.getFloat(); buf.getFloat(); // direction
            float yaw = buf.getFloat(); // rotation.yaw

            var entity = new BattleReport.MinimapEntity(entityId, x, y, yaw, 0);
            frames.add(new BattleReport.MinimapFrame(raw.clock().seconds(), List.of(entity)));
        } catch (Exception ignored) {}
    }
}
