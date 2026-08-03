package com.wows.replay.dumper;

import com.wows.replay.JsonMapper;
import com.wows.replay.ReplayException;
import com.wows.replay.ReplayFile;
import com.wows.replay.ReplayVersionMismatchException;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.packet.Packet;
import com.wows.replay.packet.Parser;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 回放分析入口，对标 wows-toolkit 的 replay-dumper 管线。
 *
 * <p>遍历回放的所有数据包，解码、统计并生成
 * {@link com.wows.replay.ingest.report.BattleReport}。需要 {@link EntitySpecProvider}
 * 做实体属性解码，可选注入 {@link GameConstantsProvider}（常量名称解析）。</p>
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

    /** 快速分析：等价于默认配置下的 {@link #analyze(ReplayFile)}，需要已注入的
     * {@link EntitySpecProvider}（缺少 spec 时 {@link #analyze(ReplayFile)} 会抛出
     * {@link IllegalArgumentException}）。 */
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
        var report = buildBattleReport(replay);
        return config.prettyPrint() ? JsonMapper.toPrettyJson(report) : JsonMapper.toJson(report);
    }

    // ── 文档化战报（replay-parser-battle-report.md into_report）────────────

    /**
     * 分析回放并返回结构化 {@link com.wows.replay.ingest.report.BattleReport}。
     *
     * <p>对标 Rust {@code BattleWorld::into_report()}：驱动一个 {@link BattleWorld}
     * 处理全部包，结束后 finish + {@code BattleReportBuilder} 装配出独立的终局快照。
     * 需要 {@link EntitySpecProvider} 做实体属性/方法解码。</p>
     */
    public com.wows.replay.ingest.report.BattleReport buildBattleReport(ReplayFile replay) throws ReplayException {
        verifyExpectedBuild(replay);
        if (specProvider == null) {
            throw new IllegalArgumentException("buildBattleReport 需要 EntitySpecProvider");
        }
        var parser = new Parser(specProvider, replay.version());
        var world = new BattleWorld(replay.meta(), replay.version(), constantsProvider);
        var decoder = new PacketDecoder(replay.version());

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());
        }
        world.finish();

        return new com.wows.replay.ingest.report.BattleReportBuilder(world, replay.meta()).build();
    }

    /**
     * 版本门禁（§5.1 / §12.4.1）：clientVersionFromExe 按 {@code ,} 拆 4 段，
     * 第 4 段（build）必须等于 {@link ReplayAnalyzerConfig#expectedBuild()}，
     * 否则拒绝解析。未配置 expectedBuild 时跳过校验。
     */
    private void verifyExpectedBuild(ReplayFile replay) throws ReplayVersionMismatchException {
        String expected = config.expectedBuild();
        if (expected == null || expected.isBlank()) return;

        String clientVersion = replay.meta().clientVersionFromExe();
        String[] parts = clientVersion != null ? clientVersion.split(",") : new String[0];
        String actualBuild = parts.length >= 4 ? parts[3] : "";
        if (!expected.equals(actualBuild)) {
            throw new ReplayVersionMismatchException(
                "回放版本 build 不匹配：期望 " + expected + "，实际 " + actualBuild
                    + "（clientVersionFromExe=" + clientVersion + "）");
        }
    }
}
