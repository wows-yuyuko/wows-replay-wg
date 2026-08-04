package com.wows.replay.dumper;

import com.wows.replay.JsonMapper;
import com.wows.replay.JsonConstantsProvider;
import com.wows.replay.ReplayFile;
import com.wows.replay.data.BattleResultsResolver;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.dumper.minimap.MinimapExtractor;
import com.wows.replay.dumper.minimap.MinimapOutput;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.ingest.report.BattleReport;
import com.wows.replay.ingest.report.BattleReportBuilder;
import com.wows.replay.packet.Packet;
import com.wows.replay.packet.Parser;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
        boolean vehicleEvents,
        boolean battleResults,
        /** minimap 字段 brotli 压缩等级 0-11，null 表示不压缩。 */
        Integer compressLevel
    ) {
        public static final Options DEFAULT = new Options(false, 7, false, false, false, null);
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
    public String dumpJson(ReplayFile replay, Options options) {
        var world = parseWorld(replay);
        var report = new BattleReportBuilder(world, replay.meta()).build();
        var out = assemble(replay, world, report, options);
        return JsonMapper.toJson(out);
    }

    public String dumpPrettyJson(ReplayFile replay, Options options) {
        var world = parseWorld(replay);
        var report = new BattleReportBuilder(world, replay.meta()).build();
        return JsonMapper.toPrettyJson(assemble(replay, world, report, options));
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
        // 战报常量解析（对标 pipeline.rs）：解析 battle_results 供 results_info/private_results_info 富化
        var constantsTree = loadConstants();
        JsonNode resolvedResults = null;
        JsonNode resolvedPlayers = null;
        Map<String, JsonNode> resolvedPrivate = new LinkedHashMap<>();
        if (report.battleResults() != null) {
            try {
                JsonNode raw = JsonMapper.readTree(report.battleResults());
                resolvedResults = constantsTree != null ? BattleResultsResolver.resolve(raw, constantsTree) : raw;
                JsonNode publicInfo = resolvedResults.get("playersPublicInfo");
                resolvedPlayers = publicInfo != null && publicInfo.isObject() ? publicInfo : null;
                resolvedPrivate = resolvePrivatePlayers(resolvedResults, constantsTree, report.selfPlayer().dbId());
            } catch (Exception e) {
                log.warn("battle_results 解析失败: {}", e.toString());
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("arena_id", report.arenaId());
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
        out.put("players", buildPlayers(report, resolvedPlayers, resolvedPrivate));
        out.put("game_events", buildGameEvents(world));
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
        if (!resolvedPrivate.isEmpty()) {
            out.put("playersPrivateInfo", resolvedPrivate);
        }
        if (options.battleResults() && resolvedResults != null) {
            out.put("battle_results", resolvedResults);
        }

        if (options.minimap()) {
            var mm = new MinimapExtractor(specProvider, constants, replay, options.minimapStep()).extract();
            if (options.compressLevel() != null) {
                int level = Math.min(11, Math.max(0, options.compressLevel()));
                out.put("frames", compressMinimapField(mm.frames(), level));
                out.put("firing_events", compressMinimapField(mm.firingEvents(), level));
                out.put("damage_events", compressMinimapField(mm.damageEvents(), level));
                out.put("shot_hits", compressMinimapField(mm.shotHits(), level));
            } else {
                out.put("frames", mm.frames());
                out.put("firing_events", mm.firingEvents());
                out.put("damage_events", mm.damageEvents());
                out.put("shot_hits", mm.shotHits());
            }
            out.put("dead_ships", mm.deadShips());
            out.put("battle_stage", mm.battleStage());
            out.put("winning_team", mm.winningTeam());
            out.put("scoring_rules", mm.scoringRules());
        }

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

    /** 加载 constants.json（GameConstantsProvider 为 JsonConstantsProvider 时直接用，否则从 game-data 读）。 */
    private JsonNode loadConstants() {
        if (constants instanceof JsonConstantsProvider jcp) {
            return jcp.root();
        }
        if (gameDataBase != null) {
            var p = gameDataBase.resolve("constants.json");
            if (Files.exists(p)) {
                try {
                    return JsonMapper.readTree(Files.readAllBytes(p));
                } catch (Exception e) {
                    log.warn("读取 constants.json 失败 {}: {}", p, e.toString());
                }
            }
        }
        return null;
    }

    /**
     * playersPrivateInfo / privateDataList → db_id → 具名对象。
     * 旧格式对象直接合并；新格式扁平数组按 PLAYER_PRIVATE_RESULTS_INDICES + BR_NESTED 解析
     * 并挂到录制玩家 db_id（对标 pipeline.rs:451-525）。
     */
    private static Map<String, JsonNode> resolvePrivatePlayers(JsonNode resolved, JsonNode constants, long selfDbId) {
        var merged = new LinkedHashMap<String, JsonNode>();
        for (String key : List.of("playersPrivateInfo", "privateDataList")) {
            JsonNode node = resolved != null ? resolved.get(key) : null;
            if (node == null) continue;
            if (node.isObject()) {
                for (var prop : node.properties()) merged.put(prop.getKey(), prop.getValue());
                break;
            }
            if (node.isArray() && constants != null) {
                ObjectNode obj = BattleResultsResolver.resolveFlatPrivate((ArrayNode) node, constants);
                merged.put(String.valueOf(selfDbId), obj);
                break;
            }
        }
        return merged;
    }

    /** players 装配（对标 Rust pipeline.rs player_json）：玩家字段 + vehicle{ship_id/modernizations/consumables/exteriors/results_info/private_results_info}。 */
    static List<Map<String, Object>> buildPlayers(BattleReport report,
                                                  JsonNode resolvedPlayers,
                                                  Map<String, JsonNode> resolvedPrivate) {
        var players = new ArrayList<Map<String, Object>>();
        for (var p : report.players()) {
            var pm = new LinkedHashMap<String, Object>();
            pm.put("db_id", p.dbId());
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
                JsonNode info = lookupPlayerInfo(resolvedPlayers, p.dbId(), p.username());
                if (info != null) {
                    v.put("results_info", info);
                }
                if (resolvedPrivate != null && resolvedPrivate.get(String.valueOf(p.dbId())) != null) {
                    v.put("private_results_info", resolvedPrivate.get(String.valueOf(p.dbId())));
                }
                pm.put("vehicle", v);
            }
            players.add(pm);
        }
        return players;
    }

    /**
     * playersPublicInfo 关联：优先 db_id，其次 username 兜底
     * （15.6 战报 account id 与竞技场 dbID 命名空间不同，见 ReplayDumper 调试）。
     */
    private static JsonNode lookupPlayerInfo(JsonNode resolvedPlayers, long dbId, String username) {
        if (resolvedPlayers == null || !resolvedPlayers.isObject()) return null;
        JsonNode info = resolvedPlayers.get(String.valueOf(dbId));
        if (info != null) return info;
        if (username != null) {
            for (var prop : resolvedPlayers.properties()) {
                JsonNode v = prop.getValue();
                if (v.isObject()) {
                    for (var f : v.properties()) {
                        if (f.getValue().isTextual() && username.equals(f.getValue().textValue())) {
                            return v;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** game_events 时间线（对标 Rust pipeline.rs）：consumable/kill/chat 按 clock 排序。 */
    static List<Map<String, Object>> buildGameEvents(BattleWorld world) {
        var events = new ArrayList<Map<String, Object>>();

        for (var e : world.consumableLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("entity_id", e.entityId());
            data.put("db_id", e.dbId());
            data.put("username", e.username());
            data.put("consumable", e.consumableId());
            data.put("activated_at", e.clock());
            data.put("duration", e.duration());
            events.add(event("consumable", e.clock(), data));
        }

        for (var k : world.killLog()) {
            var killer = new LinkedHashMap<String, Object>();
            killer.put("db_id", k.killerDbId());
            killer.put("entity_id", k.killerEid());
            killer.put("username", k.killerName());
            var victim = new LinkedHashMap<String, Object>();
            victim.put("db_id", k.victimDbId());
            victim.put("entity_id", k.victimEid());
            victim.put("username", k.victimName());
            var data = new LinkedHashMap<String, Object>();
            data.put("killer", killer);
            data.put("victim", victim);
            data.put("cause", k.cause());
            events.add(event("kill", k.clock(), data));
        }

        for (var c : world.chatLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("entity_id", c.entityId());
            data.put("db_id", c.dbId());
            data.put("username", c.senderName());
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
            } catch (NumberFormatException ignored) {}
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
            try { return Integer.parseInt(parent.getAttribute(name)); }
            catch (NumberFormatException ignored) {}
        }
        var nl = parent.getElementsByTagName(name);
        if (nl.getLength() > 0) {
            try { return Integer.parseInt(nl.item(0).getTextContent().trim()); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
