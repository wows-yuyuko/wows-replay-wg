package com.wows.replay.dumper;

import com.wows.replay.JsonConstantsProvider;
import com.wows.replay.JsonMapper;
import com.wows.replay.ReplayFile;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.decode.PlayerStateData;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.model.Version;
import com.wows.replay.packet.Packet;
import com.wows.replay.packet.Parser;
import com.wows.replay.spec.GameDataCache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replay dumper 鈥?瀵规爣 Rust {@code wows-toolkit} 鐨?replay-dumper 绠＄嚎銆? *
 * <p>杈撳嚭 JSON 瀛楁涓ユ牸瀵归綈 Rust {@code build_json_output} + {@code player_json}銆? * 浠呮彁渚?Java 绔彲瑙ｆ瀽鐨勬暟鎹紙鏃?GameParams 瑙ｆ瀽锛歴hip_name/ship_index/鑸拌埞瑁呭涓嶈緭鍑猴級銆?/p>
 */
public final class Dumper {

    private Dumper() {}

    // 鈹€鈹€ 鍏ュ彛 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    public static String dump(Path replayPath, Path gameDataBase, DumperConfig cfg) throws IOException, com.wows.replay.ReplayException {
        return dump(Files.readAllBytes(replayPath), gameDataBase, cfg);
    }

    public static String dump(byte[] replayBytes, Path gameDataBase, DumperConfig cfg) throws IOException, com.wows.replay.ReplayException {
        var replay = ReplayFile.fromBytes(replayBytes);
        var version = replay.version();

        // 瀹氫綅 data-{version}/live
        Path live = findGameData(gameDataBase, version);
        if (live == null) throw new IOException("game data not found for " + version + " under " + gameDataBase);

        var cache = GameDataCache.withMaxSize(4);
        var key = GameDataCache.VersionKey.from(live);
        var specs = cache.entitySpecs(key, live);
        JsonConstantsProvider constants = cfg.constantsFile() != null
                ? JsonConstantsProvider.fromFile(cfg.constantsFile())
                : cache.constants(key, live);

        // 鈹€鈹€ 瑙ｆ瀽 + 椹卞姩 BattleWorld 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
        var world = new BattleWorld(replay.meta(), version);
        var parser = new Parser(specs, version);
        var decoder = new PacketDecoder(version);

        int spaceSize = SpaceSettings.parse(live, replay.meta().mapName());
        float battleStart = cfg.minimap() ? findBattleStart(parser, decoder, replay) : 0f;
        MinimapCollector minimap = cfg.minimap() ? new MinimapCollector(cfg.minimapStep(), battleStart) : null;

        String battleResultsJson = null;
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet.payload() instanceof Packet.InvalidPayload) continue;
            var decoded = decoder.decode(packet);
            if (decoded instanceof DecodedPayload.BattleResultsPayload br) {
                battleResultsJson = br.json();
            }
            world.process(decoded, raw.clock());
            if (minimap != null) minimap.tick(world, raw.clock().seconds());
        }
        world.finish();

        // 鈹€鈹€ 鏋勫缓 JSON 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
        var root = JsonMapper.getMapper().createObjectNode();
        var meta = replay.meta();

        if (world.arenaId() != null) {
            try { root.put("arena_id", Long.parseLong(world.arenaId())); }
            catch (NumberFormatException e) { root.put("arena_id", 0L); }
        } else {
            root.put("arena_id", 0L);
        }
        root.put("date_time", meta.dateTime());
        root.put("version", version.toString());
        root.put("map_id", meta.mapId());
        root.put("map_name", meta.mapName());
        if (spaceSize > 0) root.put("space_size", spaceSize);
        root.put("game_mode", meta.scenario());
        if (meta.gameType() != null) root.put("game_type", meta.gameType());
        if (world.matchGroup() != null) root.put("match_group", world.matchGroup());
        setRecognized(root, "match_result", matchResultNode(world.matchResult(), world.winningTeam()));
        setRecognized(root, "finish_type", recognizedNode(world.finishType()));

        // durations
        root.put("max_duration", world.maxDuration() != null ? world.maxDuration().intValue() : 0);
        if (world.playedDuration() != null) putF(root, "played_duration", world.playedDuration());
        if (world.extraDuration() != null) putF(root, "extra_duration", world.extraDuration());

        // team_scores / capture_points / 鐜
        root.set("team_scores", teamScores(world));
        root.set("capture_points", capturePoints(world));
        root.set("buildings", JsonMapper.getMapper().createArrayNode());
        root.set("captured_buffs", JsonMapper.getMapper().createArrayNode());
        root.set("local_weather_zones", JsonMapper.getMapper().createArrayNode());
        root.set("buff_zones", JsonMapper.getMapper().createObjectNode());

        // players / game_events / playersPrivateInfo
        root.set("players", players(world, replay, battleResultsJson, constants));
        root.set("game_events", gameEvents(world, constants));
        if (battleResultsJson != null) {
            var priv = resolvePrivateInfo(battleResultsJson, constants, selfDbId(world, meta.playerName()));
            if (priv != null && !priv.isEmpty()) root.set("playersPrivateInfo", priv);
        }

        if (minimap != null) minimap.attach(root, world);

        return cfg.prettyPrint() ? JsonMapper.toPrettyJson(root) : JsonMapper.toJson(root);
    }

    // 鈹€鈹€ game data 瀹氫綅锛堝鏍?Rust find_game_data锛夆攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static Path findGameData(Path base, Version version) {
        String v = version.major() + "." + version.minor() + "." + version.patch() + "."
            + version.build();
        // 15.x 鐩綍甯镐负 data-15.6.0.0.12830008锛坆uild 鍓嶅涓€涓?.0锛夛紝瀵规爣 Rust find_game_data
        List<String> candidates = new ArrayList<>();
        candidates.add(v);
        int dot = v.lastIndexOf('.');
        if (dot >= 0) candidates.add(v.substring(0, dot) + ".0" + v.substring(dot));
        for (var cand : candidates) {
            var p = base.resolve("data-" + cand).resolve("live");
            if (Files.isDirectory(p)) return p;
        }
        var prefix = "data-" + version.major() + "." + version.minor() + "." + version.patch() + ".";
        try (var entries = Files.newDirectoryStream(base, prefix + "*")) {
            Path best = null;
            long bestBuild = -1;
            for (var entry : entries) {
                if (!Files.isDirectory(entry)) continue;
                var name = entry.getFileName().toString();
                var buildStr = name.substring(prefix.length());
                try {
                    long b = Long.parseLong(buildStr);
                    if (b > bestBuild) { bestBuild = b; best = entry.resolve("live"); }
                } catch (NumberFormatException ignored) {}
            }
            return best;
        } catch (IOException e) {
            return null;
        }
    }

    // 鈹€鈹€ Recognized / match_result 搴忓垪鍖?鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static ObjectNode recognizedNode(String name) {
        if (name == null) return null;
        var o = JsonMapper.getMapper().createObjectNode();
        if (name.matches("FinishType\\(\\d+\\)")) {
            o.put("Unknown", name.replaceAll("^FinishType\\((\\d+)\\)$", "$1"));
        } else {
            o.put("Known", name);
        }
        return o;
    }

    static ObjectNode matchResultNode(String matchResult, Integer winningTeam) {
        if (matchResult == null) return null;
        var o = JsonMapper.getMapper().createObjectNode();
        o.put("type", matchResult);
        if (!"Draw".equals(matchResult) && winningTeam != null) {
            o.put("team_id", winningTeam);
        }
        return o;
    }

    static void setRecognized(ObjectNode parent, String key, ObjectNode node) {
        if (node != null) parent.set(key, node);
    }

    // 鈹€鈹€ team_scores / capture_points 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static ArrayNode teamScores(BattleWorld world) {
        var arr = JsonMapper.getMapper().createArrayNode();
        for (var ts : world.teamScores()) {
            var o = JsonMapper.getMapper().createObjectNode();
            o.put("score", ts.score());
            o.put("team_index", ts.teamIndex());
            arr.add(o);
        }
        return arr;
    }

    static ArrayNode capturePoints(BattleWorld world) {
        var arr = JsonMapper.getMapper().createArrayNode();
        for (var cp : world.capturePoints()) {
            var o = JsonMapper.getMapper().createObjectNode();
            o.put("both_inside", cp.bothInside);
            var type = JsonMapper.getMapper().createObjectNode();
            type.put("Known", "Control");
            o.set("control_point_type", type);
            o.put("has_invaders", cp.hasInvaders);
            o.put("index", cp.index);
            o.put("invader_team", cp.invaderTeam);
            o.put("is_enabled", cp.isEnabled);
            var pos = JsonMapper.getMapper().createObjectNode();
            putF(pos, "x", cp.position != null && cp.position.length >= 2 ? cp.position[0] : 0);
            pos.put("y", 0.0);
            putF(pos, "z", cp.position != null && cp.position.length >= 2 ? cp.position[1] : 0);
            o.set("position", pos);
            var progress = JsonMapper.getMapper().createArrayNode();
            progress.add((double) cp.progress);
            progress.add(0.0);
            o.set("progress", progress);
            putF(o, "radius", cp.radius);
            o.put("team_id", cp.teamId);
            arr.add(o);
        }
        return arr;
    }

    // 鈹€鈹€ game_events 鏃堕棿绾?鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static ArrayNode gameEvents(BattleWorld world, JsonConstantsProvider constants) {
        var events = new ArrayList<ObjectNode>();
        float start = world.battleStartClock() != null ? world.battleStartClock() : 0f;
        // Rust 的 entity_to_player 只来自竞技场名册实体（initial_state.entity_id -> db_id），
        // 不含录制者本地 Avatar（CellPlayerCreate 链接）-> 对非名册实体返回 db_id=0。
        // Rust db_id = key 0（accountDBID）；Java 的 psd.accountDbId() 即 key 0。
        var rosterEntity = new LinkedHashMap<Integer, com.wows.replay.ingest.BattleWorld.PlayerLink>();
        for (var psd : world.arenaPlayers().values()) {
            rosterEntity.put(psd.entityId(),
                new com.wows.replay.ingest.BattleWorld.PlayerLink(psd.accountDbId(), psd.username()));
        }

        for (var c : world.consumableLog()) {
            var data = JsonMapper.getMapper().createObjectNode();
            var link = rosterEntity.get(c.entityId());
            putF(data, "activated_at", elapsed(c.clock(), start));
            data.set("consumable", recognizedNode(consumableName(constants, (int) c.consumableId())));
            data.put("db_id", link != null ? link.dbId() : 0L);
            putF(data, "duration", c.duration());
            data.put("entity_id", c.entityId());
            data.put("username", link != null && link.username() != null ? link.username() : "");
            var ev = JsonMapper.getMapper().createObjectNode();
            putF(ev, "clock", elapsed(c.clock(), start));
            ev.set("data", data);
            ev.put("type", "consumable");
            events.add(ev);
        }

        for (var k : world.killLog()) {
            var data = JsonMapper.getMapper().createObjectNode();
            data.set("cause", recognizedNode(deathCauseName(constants, k.cause())));
            var killer = JsonMapper.getMapper().createObjectNode();
            var kl = rosterEntity.get(k.killerEid());
            killer.put("db_id", kl != null ? kl.dbId() : 0L);
            killer.put("entity_id", k.killerEid());
            killer.put("username", kl != null && kl.username() != null ? kl.username() : "");
            var victim = JsonMapper.getMapper().createObjectNode();
            var vl = rosterEntity.get(k.victimEid());
            victim.put("db_id", vl != null ? vl.dbId() : 0L);
            victim.put("entity_id", k.victimEid());
            victim.put("username", vl != null && vl.username() != null ? vl.username() : "");
            data.set("killer", killer);
            data.set("victim", victim);
            var ev = JsonMapper.getMapper().createObjectNode();
            putF(ev, "clock", elapsed(k.clock(), start));
            ev.set("data", data);
            ev.put("type", "kill");
            events.add(ev);
        }

        for (var ch : world.chatLog()) {
            var data = JsonMapper.getMapper().createObjectNode();
            data.put("channel", chatChannel(ch.channel()));
            var link = rosterEntity.get(ch.entityId());
            data.put("db_id", link != null ? link.dbId() : 0L);
            data.put("entity_id", ch.entityId());
            data.put("message", ch.message());
            data.put("username", ch.senderName());
            var ev = JsonMapper.getMapper().createObjectNode();
            putF(ev, "clock", elapsed(ch.clock(), start));
            ev.set("data", data);
            ev.put("type", "chat");
            events.add(ev);
        }

        events.sort((a, b) -> Float.compare(a.path("clock").asFloat(), b.path("clock").asFloat()));

        var arr = JsonMapper.getMapper().createArrayNode();
        events.forEach(arr::add);
        return arr;
    }

    static float elapsed(float gameClock, float battleStart) {
        return Math.max(0f, gameClock - battleStart);
    }

    /** 褰曞埗鐜╁鍚嶅唽 db_id锛堝鏍?Rust self_player().initial_state().db_id()锛夈€?*/
    static long selfDbId(BattleWorld world, String playerName) {
        if (playerName != null) {
            for (var psd : world.arenaPlayers().values()) {
                if (playerName.equals(psd.username())) return psd.accountDbId();
            }
        }
        return 0L;
    }

    /** 浠?double 绮惧害杈撳嚭 float锛屽榻?serde f32锛堝 1153.5740966796875锛夈€?*/
    static void putF(ObjectNode o, String key, float v) {
        o.put(key, (double) v);
    }

    static String chatChannel(String audience) {
        if (audience == null) return "Unknown(null)";
        return switch (audience) {
            case "battle_common" -> "Global";
            case "battle_team" -> "Team";
            case "battle_prebattle" -> "Division";
            default -> "Unknown(" + audience + ")";
        };
    }

    static String consumableName(JsonConstantsProvider c, int id) {
        // Rust: id -> CONSUMABLE_IDS name -> Consumable 枚举变体名（如 "fighter" -> "CatapultFighter"）。
        var source = c.consumableName(id).orElse(null);
        if (source == null) return String.valueOf(id);
        return CONSUMABLE_ENUM_NAMES.getOrDefault(source, source);
    }

    /** Rust Consumable::from_consumable_type 的 name->枚举变体名 映射（序列化用变体名）。 */
    private static final Map<String, String> CONSUMABLE_ENUM_NAMES = Map.ofEntries(
        Map.entry("crashCrew", "DamageControl"),
        Map.entry("scout", "SpottingAircraft"),
        Map.entry("airDefenseDisp", "DefensiveAntiAircraft"),
        Map.entry("speedBoosters", "SpeedBoost"),
        Map.entry("artilleryBoosters", "MainBatteryReloadBooster"),
        Map.entry("smokeGenerator", "Smoke"),
        Map.entry("regenCrew", "RepairParty"),
        Map.entry("fighter", "CatapultFighter"),
        Map.entry("sonar", "HydroacousticSearch"),
        Map.entry("torpedoReloader", "TorpedoReloadBooster"),
        Map.entry("rls", "Radar"),
        Map.entry("invulnerable", "Invulnerable"),
        Map.entry("healForsage", "HealForsage"),
        Map.entry("callFighters", "CallFighters"),
        Map.entry("regenerateHealth", "RegenerateHealth"),
        Map.entry("subsOxygenRegen", "SubsOxygenRegen"),
        Map.entry("subsWaveGunBoost", "SubsWaveGunBoost"),
        Map.entry("subsFourthState", "SubsFourthState"),
        Map.entry("depthCharges", "DepthCharges"),
        Map.entry("buff", "Buff"),
        Map.entry("buffsShift", "BuffsShift"),
        Map.entry("circleWave", "CircleWave"),
        Map.entry("goDeep", "GoDeep"),
        Map.entry("weaponReloadBooster", "WeaponReloadBooster"),
        Map.entry("hydrophone", "Hydrophone"),
        Map.entry("fastRudders", "EnhancedRudders"),
        Map.entry("subsEnergyFreeze", "ReserveBattery"),
        Map.entry("groupAuraBuff", "GroupAuraBuff"),
        Map.entry("affectedBuffAura", "AffectedBuffAura"),
        Map.entry("invisibilityExtraBuffConsumable", "InvisibilityExtraBuff"),
        Map.entry("submarineLocator", "SubmarineSurveillance"),
        Map.entry("planeSmokeGenerator", "PlaneSmokeGenerator"),
        Map.entry("minefield", "Minefield"),
        Map.entry("reconnaissanceSquad", "ReconnaissanceSquad"),
        Map.entry("smokePlane", "SmokePlane"),
        Map.entry("tacticalBuff", "TacticalBuff"),
        Map.entry("planeBuff", "PlaneBuff"),
        Map.entry("Any", "Any"),
        Map.entry("All", "All"),
        Map.entry("Special", "Special"),
        Map.entry("trigger1", "Trigger1"), Map.entry("trigger2", "Trigger2"),
        Map.entry("trigger3", "Trigger3"), Map.entry("trigger4", "Trigger4"),
        Map.entry("trigger5", "Trigger5"), Map.entry("trigger6", "Trigger6"),
        Map.entry("trigger7", "Trigger7"), Map.entry("trigger8", "Trigger8"),
        Map.entry("trigger9", "Trigger9"),
        Map.entry("tacticalTrigger1", "TacticalTrigger1"), Map.entry("tacticalTrigger2", "TacticalTrigger2"),
        Map.entry("tacticalTrigger3", "TacticalTrigger3"), Map.entry("tacticalTrigger4", "TacticalTrigger4"),
        Map.entry("tacticalTrigger5", "TacticalTrigger5"), Map.entry("tacticalTrigger6", "TacticalTrigger6"),
        Map.entry("planeTrigger1", "PlaneTrigger1"), Map.entry("planeTrigger2", "PlaneTrigger2"),
        Map.entry("planeTrigger3", "PlaneTrigger3")
    );

    static String deathCauseName(JsonConstantsProvider c, int id) {
        // Rust: DEATH_REASONS name -> DeathCause 枚举变体名（如 "HE_SHELL" -> "HeShell"）。
        var source = c.deathReasonName(id).orElse(null);
        if (source == null) return String.valueOf(id);
        return DEATH_CAUSE_ENUM_NAMES.getOrDefault(source, source);
    }

    /** Rust DeathCause::from_name 的 DEATH_REASONS 名->枚举变体名 映射。 */
    private static final Map<String, String> DEATH_CAUSE_ENUM_NAMES = Map.ofEntries(
        Map.entry("NONE", "None"),
        Map.entry("ARTILLERY", "Artillery"),
        Map.entry("ATBA", "Secondaries"),
        Map.entry("TORPEDO", "Torpedo"),
        Map.entry("BOMB", "DiveBomber"),
        Map.entry("TBOMB", "AerialTorpedo"),
        Map.entry("BURNING", "Fire"),
        Map.entry("RAM", "Ramming"),
        Map.entry("TERRAIN", "Terrain"),
        Map.entry("FLOOD", "Flooding"),
        Map.entry("MIRROR", "Mirror"),
        Map.entry("SEA_MINE", "SeaMine"),
        Map.entry("SPECIAL", "Special"),
        Map.entry("DBOMB", "DepthCharge"),
        Map.entry("ROCKET", "AerialRocket"),
        Map.entry("DETONATE", "Detonation"),
        Map.entry("HEALTH", "Health"),
        Map.entry("AP_SHELL", "ApShell"),
        Map.entry("HE_SHELL", "HeShell"),
        Map.entry("CS_SHELL", "CsShell"),
        Map.entry("FEL", "Fel"),
        Map.entry("PORTAL", "Portal"),
        Map.entry("SKIP_BOMB", "SkipBombs"),
        Map.entry("SECTOR_WAVE", "SectorWave"),
        Map.entry("ACID", "Acid"),
        Map.entry("LASER", "Laser"),
        Map.entry("MATCH", "Match"),
        Map.entry("TIMER", "Timer"),
        Map.entry("ADBOMB", "AerialDepthCharge"),
        Map.entry("EVENT_1", "Event1"), Map.entry("EVENT_2", "Event2"),
        Map.entry("EVENT_3", "Event3"), Map.entry("EVENT_4", "Event4"),
        Map.entry("EVENT_5", "Event5"), Map.entry("EVENT_6", "Event6"),
        Map.entry("MISSILE", "Missile")
    );

    // 鈹€鈹€ players 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static ArrayNode players(BattleWorld world, ReplayFile replay, String battleResultsJson,
                             JsonConstantsProvider constants) {
        var shipIdByDbId = new LinkedHashMap<Long, Long>();
        if (replay.meta().vehicles() != null) {
            for (var v : replay.meta().vehicles()) {
                shipIdByDbId.put(Integer.toUnsignedLong(v.id().value()), v.shipId().value());
            }
        }

        JsonNode publicInfo = null;
        if (battleResultsJson != null) {
            try {
                var br = JsonMapper.readTree(battleResultsJson);
                publicInfo = br.get("playersPublicInfo");
            } catch (Exception ignored) {}
        }

        var arr = JsonMapper.getMapper().createArrayNode();
        var sorted = new ArrayList<>(world.arenaPlayers().values());
        sorted.sort((a, b) -> Long.compare(a.dbId(), b.dbId()));

        for (var psd : sorted) {
            var player = JsonMapper.getMapper().createObjectNode();

            var initialState = JsonMapper.getMapper().createObjectNode();
            initialState.put("clan", psd.clan());
            initialState.put("clan_color", psd.clanColor());
            initialState.put("clan_id", psd.clanId());
            initialState.put("db_id", psd.accountDbId());
            initialState.put("entity_id", psd.entityId());
            initialState.put("is_abuser", psd.isAbuser());
            initialState.put("is_bot", psd.isBot());
            initialState.put("is_hidden", psd.isHidden());
            initialState.put("max_health", psd.maxHealth());
            initialState.put("meta_ship_id", psd.dbId());
            initialState.put("realm", psd.realm() != null ? psd.realm() : "");
            initialState.put("team_id", psd.teamId());
            initialState.put("username", psd.username());
            initialState.set("human_properties", JsonMapper.getMapper().createObjectNode());
            var rawNames = JsonMapper.getMapper().createObjectNode();
            for (var e : psd.rawWithNames().entrySet()) {
                setPickleValue(rawNames, e.getKey(), e.getValue());
            }
            initialState.set("raw_with_names", rawNames);
            player.set("initial_state", initialState);

            var vehicle = JsonMapper.getMapper().createObjectNode();
            long shipId = shipIdByDbId.getOrDefault(psd.dbId(), 0L);
            vehicle.put("ship_id", shipId);
            vehicle.set("modernizations", JsonMapper.getMapper().createArrayNode());
            vehicle.set("consumables", JsonMapper.getMapper().createArrayNode());
            vehicle.set("exteriors", JsonMapper.getMapper().createArrayNode());
            vehicle.set("commander_skills", JsonMapper.getMapper().createArrayNode());
            if (publicInfo != null && publicInfo.has(String.valueOf(psd.dbId()))) {
                vehicle.set("results_info", publicInfo.get(String.valueOf(psd.dbId())));
            }
            player.set("vehicle", vehicle);

            var pi = world.players().get(psd.dbId());
            player.put("relation", pi != null ? pi.relation : 0);
            player.set("connection_change_info", JsonMapper.getMapper().createArrayNode());

            arr.add(player);
        }
        return arr;
    }

    static void setPickleValue(ObjectNode parent, String key, Object value) {
        if (value == null) { parent.putNull(key); return; }
        if (value instanceof Long l) { parent.put(key, l); return; }
        if (value instanceof Integer i) { parent.put(key, i); return; }
        if (value instanceof Double d) { parent.put(key, d); return; }
        if (value instanceof Float f) { parent.put(key, f); return; }
        if (value instanceof Boolean b) { parent.put(key, b); return; }
        if (value instanceof String s) { parent.put(key, s); return; }
        if (value instanceof byte[] bytes) { parent.put(key, java.util.Base64.getEncoder().encodeToString(bytes)); return; }
        if (value instanceof List<?> list) {
            var arr = JsonMapper.getMapper().createArrayNode();
            for (var item : list) {
                if (item == null) { arr.addNull(); continue; }
                if (item instanceof Number || item instanceof Boolean || item instanceof String) {
                    arr.add(String.valueOf(item));
                } else {
                    var obj = JsonMapper.getMapper().createObjectNode();
                    obj.put("value", String.valueOf(item));
                    arr.add(obj);
                }
            }
            parent.set(key, arr);
            return;
        }
        parent.put(key, String.valueOf(value));
    }

    // 鈹€鈹€ playersPrivateInfo 瑙ｆ瀽锛堝鏍?pipeline.rs锛夆攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    static ObjectNode resolvePrivateInfo(String battleResultsJson, JsonConstantsProvider constants, long selfDbId) {
        JsonNode br;
        try {
            br = JsonMapper.readTree(battleResultsJson);
        } catch (Exception e) {
            return null;
        }
        var merged = JsonMapper.getMapper().createObjectNode();
        for (String key : List.of("playersPrivateInfo", "privateDataList")) {
            var node = br.get(key);
            if (node == null) continue;
            if (node.isObject()) {
                for (var e : node.properties()) merged.set(e.getKey(), e.getValue());
                return merged;
            }
            if (node.isArray()) {
                var indices = constants.section("PLAYER_PRIVATE_RESULTS_INDICES");
                if (indices == null || !indices.isObject()) return merged.isEmpty() ? null : merged;
                var obj = JsonMapper.getMapper().createObjectNode();
                for (var e : indices.properties()) {
                    var idx = e.getValue().asInt(-1);
                    if (idx >= 0 && idx < node.size()) obj.set(e.getKey(), node.get(idx));
                }
                resolveNested(obj, constants);
                merged.set(String.valueOf(selfDbId), obj);
                return merged;
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    static void resolveNested(ObjectNode obj, JsonConstantsProvider constants) {
        var nested = constants.section("BR_NESTED");
        if (nested == null || !nested.isArray()) return;
        for (var entry : nested) {
            var field = entry.path("field").asText();
            var subList = entry.path("sub_list").asText();
            if (field.isEmpty() || subList.isEmpty()) continue;
            var fieldVal = obj.get(field);
            if (fieldVal == null || !fieldVal.isArray()) continue;
            var names = constants.section(subList);
            if (names == null || !names.isArray()) continue;
            var subObj = JsonMapper.getMapper().createObjectNode();
            var arr = fieldVal;
            for (int i = 0; i < names.size() && i < arr.size(); i++) {
                var name = names.get(i);
                var nameStr = name.isTextual() ? name.asText() : (name.isObject() ? name.path("field").asText("") : "");
                if (!nameStr.isEmpty()) subObj.set(nameStr, arr.get(i));
            }
            obj.set(field, subObj);
        }
    }

    // ── Minimap（对标 Rust position.rs extract_minimap_fast）──────────────

    /** 预扫描：找到 battleStage=Waiting 的 clock（对标 Rust scan_battle_start_clock）。 */
    static float findBattleStart(Parser parser, PacketDecoder decoder, ReplayFile replay) {
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet.payload() instanceof Packet.InvalidPayload) continue;
            var decoded = decoder.decode(packet);
            if (decoded instanceof DecodedPayload.PropertyChangePayload pc
                    && pc.change().kind() == com.wows.replay.decode.PropertyDecoder.Kind.BATTLE_STAGE
                    && pc.change().value() instanceof com.wows.replay.types.ArgValue.IntVal iv
                    && iv.value() == 0) {
                return raw.clock().seconds();
            }
        }
        return 0f;
    }

    static final class MinimapCollector {
        private final int step;
        private final float battleStart;
        private final List<ObjectNode> frames = new ArrayList<>();
        private final List<ObjectNode> firingEvents = new ArrayList<>();
        private final List<ObjectNode> damageEvents = new ArrayList<>();
        private final List<ObjectNode> shotHits = new ArrayList<>();
        private final java.util.Set<String> seenSalvos = new java.util.HashSet<>();
        private float lastElapsed = Float.NEGATIVE_INFINITY;
        private int tick;
        private int damageSeen;
        private int shotHitSeen;

        MinimapCollector(int step, float battleStart) {
            this.step = step;
            this.battleStart = battleStart;
        }

        private float elapsed(float raw) {
            return raw - battleStart;
        }

        void tick(BattleWorld world, float clock) {
            if (clock == lastElapsed) return;

            var damages = world.damageEvents();
            for (int i = damageSeen; i < damages.size(); i++) {
                var ev = damages.get(i);
                var o = obj();
                putF(o, "aggressor_id", ev.aggressorId());
                putF(o, "amount", ev.amount());
                putF(o, "clock", elapsed(ev.clock()));
                o.put("victim_id", ev.victimId());
                damageEvents.add(o);
            }
            damageSeen = damages.size();

            for (var s : world.firedSalvos()) {
                String key = s.avatarId() + ":" + s.salvo().salvoId();
                if (seenSalvos.add(key)) firingEvents.add(firingEntry(s, clock));
            }

            var hits = world.shotHits();
            for (int i = shotHitSeen; i < hits.size(); i++) shotHits.add(shotHitEntry(hits.get(i)));
            shotHitSeen = hits.size();

            if (tick % step == 0) frames.add(buildFrame(world, clock));
            lastElapsed = clock;
            tick++;
        }

        void attach(ObjectNode root, BattleWorld world) {
            var mapper = JsonMapper.getMapper();
            var framesArr = mapper.createArrayNode();
            frames.forEach(framesArr::add);
            root.set("frames", framesArr);
            var firingArr = mapper.createArrayNode();
            firingEvents.forEach(firingArr::add);
            root.set("firing_events", firingArr);
            var damageArr = mapper.createArrayNode();
            damageEvents.forEach(damageArr::add);
            root.set("damage_events", damageArr);
            var shotArr = mapper.createArrayNode();
            shotHits.forEach(shotArr::add);
            root.set("shot_hits", shotArr);

            var dead = mapper.createArrayNode();
            for (var ds : world.deadShips()) {
                var o = obj();
                putF(o, "clock", elapsed(ds.clock()));
                o.put("victim_id", ds.victimId());
                putF(o, "x", ds.x());
                putF(o, "z", ds.z());
                dead.add(o);
            }
            root.set("dead_ships", dead);

            Integer stage = world.battleStageId();
            if (stage != null) {
                root.put("battle_stage", switch (stage) {
                    case 0 -> "Waiting"; case 1 -> "Battle"; case 2 -> "Results";
                    case 3 -> "Finishing"; case 4 -> "Ended"; default -> "Unknown(" + stage + ")";
                });
            }
            if (world.winningTeam() != null) root.put("winning_team", world.winningTeam());

            var sr = mapper.createObjectNode();
            sr.put("team_win_score", world.teamWinScore());
            sr.put("hold_reward", world.holdReward());
            putF(sr, "hold_period", world.holdPeriod());
            var cps = mapper.createArrayNode();
            for (int idx : world.holdCpIndices()) cps.add(idx);
            sr.set("hold_cp_indices", cps);
            root.set("scoring_rules", sr);
        }

        private ObjectNode buildFrame(BattleWorld world, float clock) {
            var fr = obj();
            putF(fr, "clock", elapsed(clock));
            var mapper = JsonMapper.getMapper();

            var ents = mapper.createArrayNode();
            for (var es : world.entities().values()) {
                if (Float.isNaN(es.minimapX)) continue;
                var o = obj();
                o.put("id", es.id.value());
                putF(o, "x", es.minimapX);
                putF(o, "y", es.minimapZ);
                putF(o, "heading", Float.isNaN(es.minimapHeading) ? es.heading : es.minimapHeading);
                putF(o, "health", es.health);
                putF(o, "max_health", es.maxHealth);
                o.put("team_id", es.teamId);
                o.put("visible", es.visible);
                o.put("visibility_flags", es.visibilityFlags);
                o.put("is_alive", es.isAlive);
                o.put("is_invisible", es.isInvisible);
                putF(o, "last_updated", elapsed(es.lastUpdated));
                ents.add(o);
            }
            fr.set("entities", ents);

            var planes = mapper.createArrayNode();
            for (var p : world.activePlanes().values()) {
                var o = obj();
                o.put("plane_id", p.planeId());
                o.put("owner_id", p.ownerEntityId());
                o.put("team_id", p.teamId());
                o.put("params_id", p.paramsId().value());
                putF(o, "x", p.x());
                putF(o, "z", p.z());
                putF(o, "last_updated", elapsed(p.lastUpdateAt()));
                planes.add(o);
            }
            fr.set("planes", planes);

            var torps = mapper.createArrayNode();
            for (var t : world.activeTorpedoes().values()) {
                var o = obj();
                o.put("shot_id", t.data().shotId());
                o.put("owner_id", t.data().ownerId().value());
                o.put("params_id", t.data().paramsId().value());
                o.put("salvo_id", t.data().salvoId());
                setVec3(o, "origin", t.data().origin());
                setVec3(o, "direction", t.data().direction());
                o.put("armed", t.data().armed());
                putF(o, "launched_at", elapsed(t.clock()));
                putF(o, "updated_at", elapsed(t.clock()));
                o.put("has_maneuver", t.hasManeuver());
                o.put("has_acoustic", false);
                torps.add(o);
            }
            fr.set("torpedoes", torps);

            var smoke = mapper.createArrayNode();
            for (var s : world.smokeScreens().values()) {
                var o = obj();
                o.put("entity_id", s.id.value());
                putF(o, "x", s.x);
                putF(o, "z", s.z);
                putF(o, "radius", s.smokeRadius);
                smoke.add(o);
            }
            fr.set("smoke_screens", smoke);

            var buildings = mapper.createArrayNode();
            for (var b : world.buildings()) {
                var o = obj();
                o.put("entity_id", b.entityId());
                putF(o, "x", b.x());
                putF(o, "z", b.z());
                o.put("team_id", b.teamId());
                o.put("params_id", b.paramsId());
                o.put("is_alive", b.isAlive());
                buildings.add(o);
            }
            fr.set("buildings", buildings);

            var wards = mapper.createArrayNode();
            for (var w : world.activeWards().values()) {
                var o = obj();
                o.put("plane_id", w.wardId());
                o.put("owner_id", w.ownerId().value());
                putF(o, "x", w.position().x());
                putF(o, "z", w.position().z());
                putF(o, "radius", w.radius());
                wards.add(o);
            }
            fr.set("active_wards", wards);

            var buffs = mapper.createArrayNode();
            for (var bz : world.buffZones().values()) {
                var o = obj();
                o.put("entity_id", bz.entityId());
                putF(o, "x", bz.x());
                putF(o, "z", bz.z());
                putF(o, "radius", bz.radius());
                o.put("team_id", bz.teamId());
                o.put("is_active", bz.isActive());
                if (bz.dropParamsId() != null) o.put("drop_params_id", bz.dropParamsId());
                buffs.add(o);
            }
            fr.set("buff_zones", buffs);

            var weather = mapper.createArrayNode();
            for (var wz : world.weatherZones()) {
                var o = obj();
                o.put("name", wz.name());
                putF(o, "x", wz.x());
                putF(o, "z", wz.z());
                putF(o, "radius", wz.radius());
                o.put("params_id", wz.paramsId());
                weather.add(o);
            }
            fr.set("weather_zones", weather);

            fr.set("team_scores", teamScores(world));
            fr.set("capture_points", capturePoints(world));
            if (world.timeLeft() != null) putF(fr, "time_left", world.timeLeft());

            return fr;
        }

        private ObjectNode firingEntry(com.wows.replay.ingest.BattleWorld.ArtillerySalvo s, float tickClock) {
            var o = obj();
            o.put("avatar_id", s.avatarId());
            putF(o, "clock", elapsed(tickClock));
            putF(o, "fired_at", elapsed(s.clock()));
            o.put("owner_id", s.salvo().ownerId().value());
            o.put("params_id", s.salvo().paramsId().value());
            o.put("salvo_id", s.salvo().salvoId());
            var shots = JsonMapper.getMapper().createArrayNode();
            for (var d : s.salvo().shots()) {
                var so = obj();
                setVec3(so, "origin", d.origin());
                putF(so, "pitch", d.pitch());
                putF(so, "speed", d.speed());
                setVec3(so, "target", d.target());
                so.put("shot_id", d.shotId());
                so.put("gun_barrel_id", d.gunBarrelId());
                putF(so, "server_time_left", d.serverTimeLeft());
                putF(so, "shooter_height", d.shooterHeight());
                putF(so, "hit_distance", d.hitDistance());
                shots.add(so);
            }
            o.set("shots", shots);
            return o;
        }

        private ObjectNode shotHitEntry(com.wows.replay.ingest.BattleWorld.ShotHitRecord h) {
            var o = obj();
            putF(o, "clock", elapsed(h.clock()));
            o.putNull("fired_at");
            var ht = obj();
            var collision = obj();
            collision.put("Unknown", String.valueOf(h.hit().hitType().collisionId()));
            var shellHit = obj();
            shellHit.put("Unknown", String.valueOf(h.hit().hitType().shellHitId()));
            ht.set("collision", collision);
            ht.put("raw", h.hit().hitType().raw());
            ht.set("shell_hit", shellHit);
            o.set("hit_type", ht);
            o.put("owner_id", h.hit().ownerId().value());
            setVec3(o, "position", h.hit().position());
            o.put("shot_id", h.hit().shotId());
            if (h.hit().terminalBallistics() != null) {
                o.set("terminal_ballistics", terminalBallistics(h.hit().terminalBallistics()));
            } else {
                o.putNull("terminal_ballistics");
            }
            o.put("victim_id", 0);
            o.set("victim_position", JsonMapper.getMapper().createArrayNode().add(0).add(0).add(0));
            return o;
        }

        private ObjectNode terminalBallistics(com.wows.replay.decode.DecodedPayload.TerminalBallistics tb) {
            var o = obj();
            setVec3(o, "position", tb.position());
            setVec3(o, "velocity", tb.velocity());
            o.put("detonator_activated", tb.detonatorActivated());
            putF(o, "material_angle", tb.materialAngle());
            return o;
        }
    }

    private static ObjectNode obj() {
        return JsonMapper.getMapper().createObjectNode();
    }

    private static void setVec3(ObjectNode o, String key, com.wows.replay.model.Vec3 v) {
        var arr = JsonMapper.getMapper().createArrayNode();
        arr.add((double) v.x());
        arr.add((double) v.y());
        arr.add((double) v.z());
        o.set(key, arr);
    }
}
