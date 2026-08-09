package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.ReplayVersionMismatchException;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapExtractor;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapMerger;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import com.shinoaki.wowsreplay.dumper.web.BattleStatsCalculator;
import com.shinoaki.wowsreplay.dumper.web.BattleTimelineCalculator;
import com.shinoaki.wowsreplay.dumper.web.ResultsInfoExtractor;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.mapped.NormalizedReplay;
import com.shinoaki.wowsreplay.ingest.mapped.ReplayMapper;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;
import com.shinoaki.wowsreplay.ingest.report.BattleReportBuilder;
import com.shinoaki.wowsreplay.merge.ParsedReplay;
import com.shinoaki.wowsreplay.merge.ReplayMerger;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * replay-dumper 管线（对标 Rust {@code replay-dumper} crate 的 Single 模式，
 * 忽略多 rep 合并），产出**单一 JSON**（Rust {@code pipeline::build_json_output}）。
 *
 * <p>装配：meta + {@link BattleReport}（含 players/capture_points/team_scores/…）+
 * {@code game_events} 时间线（consumable/kill/chat 按 clock 排序）+ 可选 minimap
 * （{@link MinimapOutput}）+ {@code space_size}（space.settings 解析）。</p>
 *
 * <p>game_params 名称解析不做（ship/装备等用原始 id，见评估）。</p>
 */
@Slf4j
public final class ReplayDumper {

    /** 管线选项（对标 Rust {@code ParseOptions} 的 Single 子集）。 */
    public record Options(
            boolean minimap,
            int minimapStep,
            boolean selfDamageStats,
            /* minimap 字段 brotli 压缩等级 0-11，null 表示不压缩。 */
            Integer compressLevel
    ) {
        public static final Options DEFAULT = new Options(false, 7, false, null);
    }

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;
    private final Path gameDataBase;

    public ReplayDumper(EntitySpecProvider specProvider, GameConstantsProvider constants, Path gameDataBase) {
        this.specProvider = specProvider;
        this.constants = constants;
        this.gameDataBase = gameDataBase;
    }

    /** 解析回放 → 单一 JSON（对标 Rust {@code parse_replay_with_options}）。 */
    public String dumpJson(ReplayFile replay, Options options) throws ReplayVersionMismatchException {
        verifyVersion(replay);
        var world = parseWorld(replay);
        var report = new BattleReportBuilder(world, replay.meta()).build();
        var out = assemble(replay, world, report, options);
        return JsonMapper.toJson(out);
    }

    public String dumpPrettyJson(ReplayFile replay, Options options) throws ReplayVersionMismatchException {
        verifyVersion(replay);
        var world = parseWorld(replay);
        var report = new BattleReportBuilder(world, replay.meta()).build();
        return JsonMapper.toPrettyJson(assemble(replay, world, report, options));
    }

    /**
     * 版本门禁：回放 client build 必须与加载的 game-data build 一致，否则拒绝解析
     * （防止用错版本的游戏数据解码，对标 ReplayVersionMismatchException）。
     * 任一 build 未知时跳过。
     */
    private void verifyVersion(ReplayFile replay) throws ReplayVersionMismatchException {
        if (gameDataBase == null) return;
        Path dataDir = gameDataBase.getParent();
        if (dataDir == null) return;
        String dataName = dataDir.getFileName().toString();
        long dataBuild = parseBuildNumber(dataName);
        if (dataBuild <= 0) return;

        String clientVersion = replay.meta().clientVersionFromExe();
        String[] parts = clientVersion != null ? clientVersion.split(",") : new String[0];
        long replayBuild = parts.length >= 4 ? parseBuildNumber(parts[3]) : 0;
        if (replayBuild > 0 && dataBuild != replayBuild) {
            throw new ReplayVersionMismatchException(
                "回放 build 与游戏数据不匹配：回放 " + replay.version() + "，数据目录 " + dataName
                    + "（拒绝解析，防止 schema 错配）");
        }
    }

    /** 从 "data-M.m.p.b" 目录名或纯数字串解析 build 号，无法解析返回 0。 */
    private static long parseBuildNumber(String s) {
        if (s == null) return 0;
        int idx = s.lastIndexOf('.');
        String build = idx >= 0 ? s.substring(idx + 1) : s;
        try {
            return Long.parseLong(build);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private BattleWorld parseWorld(ReplayFile replay) {
        var parser = new Parser(specProvider, replay.version());
        var world = new BattleWorld(replay.meta(), replay.version(), constants);
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
        return world;
    }

    // ── 装配（对标 Rust build_json_output）────────────────────────────────

    private Map<String, Object> assemble(ReplayFile replay, BattleWorld world,
                                         BattleReport report, Options options) {
        // battle_results 已由 BattleReportBuilder 用 constants.json 解析为具名对象；
        // 最终输出阶段用 ResultsInfoExtractor 把 playersPublicInfo 每个玩家精简为 BattleData。
        JsonNode battleResults = ResultsInfoExtractor.processBattleResults(report.battleResults());
        NormalizedReplay normalized = ReplayMapper.map(world, report);
        MinimapOutput mm = options.minimap()
            ? new MinimapExtractor(specProvider, constants, replay, options.minimapStep()).extract()
            : null;
        return assembleFinal(replay, report, normalized, mm, battleResults, options);
    }

    /**
     * 多视角合并解析：主视角 + alt 视角 → 最终 JSON（形状与单 replay 一致）。
     *
     * @param primary 主视角回放
     * @param alts    alt 视角回放（同场次）
     * @return 合并后的单一 JSON
     * @throws ReplayVersionMismatchException 主视角 build 与 game-data 不匹配
     */
    public String dumpMergedJson(ReplayFile primary, List<ReplayFile> alts, Options options)
            throws ReplayVersionMismatchException {
        verifyVersion(primary);
        var merger = new ReplayMerger(specProvider, constants);
        var parsed = new ArrayList<ParsedReplay>(1 + (alts == null ? 0 : alts.size()));
        parsed.add(merger.parse(primary));
        if (alts != null) {
            for (var a : alts) parsed.add(merger.parse(a));
        }
        var merged = merger.merge(parsed);
        var report = parsed.get(0).report();

        JsonNode battleResults = ResultsInfoExtractor.processBattleResults(report.battleResults());
        MinimapOutput mm = options.minimap()
            ? new MinimapMerger(specProvider, constants, replaysOf(primary, alts), options.minimapStep()).merge()
            : null;
        return JsonMapper.toJson(assembleFinal(primary, report, merged.replay(), mm, battleResults, options));
    }

    private static List<ReplayFile> replaysOf(ReplayFile primary, List<ReplayFile> alts) {
        var all = new ArrayList<ReplayFile>(1 + (alts == null ? 0 : alts.size()));
        all.add(primary);
        if (alts != null) all.addAll(alts);
        return all;
    }

    /** 公共最终输出装配：单 replay 与多视角合并共用（形状一致）。 */
    private Map<String, Object> assembleFinal(ReplayFile replay, BattleReport report,
                                              NormalizedReplay normalized, MinimapOutput mm,
                                              JsonNode battleResults, Options options) {
        var out = new LinkedHashMap<String, Object>();
        out.put("battle_results", battleResults);
        out.put("arena_id", report.arenaId());
        // 主视角用户：单 replay 为录制者(self)玩家的 meta_id
        out.put("master_meta_id", report.selfPlayer().metaId());
        out.put("date_time", replay.meta().dateTime());
        out.put("version", report.version() != null ? report.version().toString() : null);
        out.put("map_id", replay.meta().mapId());
        out.put("map_name", report.mapName());
        out.put("space_size", parseSpaceSize(gameDataBase, replay.meta().mapName()));
        out.put("game_mode", report.gameMode());
        out.put("game_type", replay.meta().gameType());
        out.put("match_group", report.matchGroup());
        out.put("match_result", report.matchResult());
        out.put("finish_type", report.finishType());
        // 映射层：实体 id → 全局一致 metaId，事件流输出只带 metaId
        List<Map<String, Object>> players = buildPlayers(report);
        JsonNode playersNode = JsonMapper.toTree(players);
        out.put("players", playersNode);
        // 最终输出阶段：webFunction 对象收纳从 WebFunction 迁移来的计算数据
        ObjectNode webFunction = JsonMapper.createObject();
        webFunction.set("battle_stats", JsonMapper.toTree(
                BattleStatsCalculator.calculate(playersNode, battleResults)));
        out.put("game_events", buildGameEvents(normalized));
        out.put("capture_points", report.capturePoints());
        out.put("buff_zones", report.buffZones());
        out.put("captured_buffs", report.capturedBuffs());
        out.put("team_scores", report.teamScores());
        out.put("buildings", report.buildings());
        out.put("local_weather_zones", report.localWeatherZones());
        out.put("max_duration", report.maxDuration());
        out.put("played_duration", report.playedDuration());
        out.put("extra_duration", report.extraDuration());
        if (options.selfDamageStats()) {
            out.put("self_damage_stats", report.selfDamageStats());
        }
        var minimap = new LinkedHashMap<String, Object>();
        if (mm != null) {
            minimap.put("frames", mm.frames());
            minimap.put("firing_events", mm.firingEvents());
            minimap.put("damage_events", mm.damageEvents());
            minimap.put("shot_hits", mm.shotHits());
            minimap.put("dead_ships", mm.deadShips());
            if (options.compressLevel() != null) {
                out.put("minimap", compressMinimapField(minimap, Math.clamp(options.compressLevel(), 0, 11)));
            } else {
                out.put("minimap", minimap);
            }
            out.put("battle_stage", mm.battleStage());
            out.put("scoring_rules", mm.scoringRules());
            // 最终输出阶段：基于流式处理产物计算累计伤害时间线与团队差距
            double duration = report.playedDuration() > 0 ? report.playedDuration() : report.maxDuration();
            webFunction.set("battle_timeline", JsonMapper.toTree(
                    BattleTimelineCalculator.calculate(playersNode, battleResults, mm, duration)));
        }

        out.put("webFunction", webFunction);
        return out;
    }

    /**
     * minimap 字段压缩（对标 Rust {@code compress_minimap_field}）：JSON → Brotli → Base64 字符串。
     */
    static String compressMinimapField(Object value, int quality) {
        byte[] json = JsonMapper.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var params = new com.aayushatharva.brotli4j.encoder.Encoder.Parameters().setQuality(quality);
        byte[] compressed;
        try {
            compressed = com.aayushatharva.brotli4j.encoder.Encoder.compress(json, params);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("brotli 压缩失败", e);
        }
        return java.util.Base64.getEncoder().encodeToString(compressed);
    }

    static {
        com.aayushatharva.brotli4j.Brotli4jLoader.ensureAvailability();
    }

    /**
     * players 装配（对标 Rust pipeline.rs player_json）：玩家字段（metaId + accountId，去掉
     * 视角相关 entity_id）+ vehicle{ship_id/modernizations/consumables/exteriors}。
     */
    static List<Map<String, Object>> buildPlayers(BattleReport report) {
        var players = new ArrayList<Map<String, Object>>();
        for (var p : report.players()) {
            var pm = new LinkedHashMap<String, Object>();

            pm.put("meta_id", p.metaId());
            pm.put("account_id", p.dbId());
            pm.put("username", p.username());
            pm.put("team_id", p.teamId());
            pm.put("relation", p.relation());
            pm.put("is_bot", p.isBot());
            var veh = p.vehicleEntity();
            if (veh != null && veh.shipConfig() != null) {
                var sc = veh.shipConfig();
                var v = new LinkedHashMap<String, Object>();
                v.put("ship_id", sc.shipParamsId());
                v.put("modernizations", sc.modernization());
                v.put("consumables", sc.consumables());
                v.put("exteriors", sc.exteriors());
                pm.put("vehicle", v);
            }
            players.add(pm);
        }
        return players;
    }

    /** game_events 时间线（对标 Rust pipeline.rs）：consumable/kill/chat 按 clock 排序，玩家身份只用 metaId。 */
    static List<Map<String, Object>> buildGameEvents(NormalizedReplay replay) {
        var events = new ArrayList<Map<String, Object>>();

        for (var e : replay.consumableLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("meta_id", e.metaId());
            data.put("username", e.username());
            data.put("consumable", e.consumableId());
            data.put("activated_at", e.clock());
            data.put("duration", e.duration());
            events.add(event("consumable", e.clock(), data));
        }

        for (var k : replay.killLog()) {
            var killer = new LinkedHashMap<String, Object>();
            killer.put("meta_id", k.killerMetaId());
            killer.put("username", k.killerName());
            var victim = new LinkedHashMap<String, Object>();
            victim.put("meta_id", k.victimMetaId());
            victim.put("username", k.victimName());
            var data = new LinkedHashMap<String, Object>();
            data.put("killer", killer);
            data.put("victim", victim);
            data.put("cause", k.cause());
            events.add(event("kill", k.clock(), data));
        }

        for (var c : replay.chatLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("meta_id", c.metaId());
            data.put("username", c.username());
            data.put("channel", c.channel());
            data.put("message", c.message());
            events.add(event("chat", c.clock(), data));
        }

        events.sort(Comparator.comparingDouble(e -> ((Number) e.get("clock")).doubleValue()));
        return events;
    }

    private static Map<String, Object> event(String type, float clock, Map<String, Object> data) {
        var ev = new LinkedHashMap<String, Object>();
        ev.put("type", type);
        ev.put("clock", clock);
        ev.put("data", data);
        return ev;
    }

    // ── space_size（对标 Rust position::parse_space_settings）────────────

    static Integer parseSpaceSize(Path gameDataBase, String mapName) {
        if (mapName == null || mapName.isBlank()) return null;
        Path[] candidates = {
                gameDataBase.resolve(mapName).resolve("space.settings"),
                gameDataBase.resolve("spaces").resolve(mapName).resolve("space.settings"),
        };
        for (var p : candidates) {
            if (Files.exists(p)) {
                try {
                    return parseSpaceSettings(p);
                } catch (Exception e) {
                    log.warn("解析 space.settings 失败 {}: {}", p, e.toString());
                    return null;
                }
            }
        }
        log.debug("未找到 space.settings（{} 或 spaces/{}）", mapName, mapName);
        return null;
    }

    private static Integer parseSpaceSettings(Path file) throws IOException {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setIgnoringComments(true);
        dbf.setIgnoringElementContentWhitespace(true);
        Document doc;
        try {
            doc = dbf.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new IOException(e);
        }
        var bounds = findTag(doc, "bounds");
        if (bounds == null) return null;
        int minX = intAttrOrChild(bounds, "minX");
        int maxX = intAttrOrChild(bounds, "maxX");
        int minY = intAttrOrChild(bounds, "minY");
        int maxY = intAttrOrChild(bounds, "maxY");

        double chunkSize = 100.0;
        var chunkNode = findTag(doc, "chunkSize");
        if (chunkNode != null) {
            try {
                chunkSize = Double.parseDouble(chunkNode.getTextContent().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        double chunksX = maxX - minX + 1;
        double chunksY = maxY - minY + 1;
        int spaceW = (int) Math.round((chunksX - 4.0) * chunkSize);
        int spaceH = (int) Math.round((chunksY - 4.0) * chunkSize);
        int spaceSize = Math.max(spaceW, spaceH);

        log.info("Map {}: bounds=({},{})..({},{}) chunk_size={} space_size={}",
                file.getParent() != null ? file.getParent().getFileName() : "", minX, minY, maxX, maxY, chunkSize, spaceSize);
        return spaceSize;
    }

    private static org.w3c.dom.Element findTag(Document doc, String name) {
        var nl = doc.getElementsByTagName(name);
        return nl.getLength() > 0 ? (org.w3c.dom.Element) nl.item(0) : null;
    }

    private static int intAttrOrChild(org.w3c.dom.Element parent, String name) {
        if (parent.hasAttribute(name)) {
            try {
                return Integer.parseInt(parent.getAttribute(name));
            } catch (NumberFormatException ignored) {
            }
        }
        var nl = parent.getElementsByTagName(name);
        if (nl.getLength() > 0) {
            try {
                return Integer.parseInt(nl.item(0).getTextContent().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
