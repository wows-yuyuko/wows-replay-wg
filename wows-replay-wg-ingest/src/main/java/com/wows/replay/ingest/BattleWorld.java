package com.wows.replay.ingest;

import com.wows.replay.ReplayMeta;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.decode.PlayerStateData;
import com.wows.replay.decode.PropertyDecoder;
import com.wows.replay.types.ArgValue;
import com.wows.replay.model.*;
import com.wows.replay.packet.*;
import com.wows.replay.pickle.PickleReader;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * BattleWorld — the central state container for replay analysis.
 *
 * <p>Mirrors Rust {@code BattleWorld} + ingest dispatch. Processes
 * {@link DecodedPayload} events, maintains entity state and resources,
 * and produces a {@link BattleReport} on finish.</p>
 *
 * <p>Architecture:
 * <pre>
 *   Parser → Packet → PacketDecoder → DecodedPayload → BattleWorld.process()
 *                                                                 ├── entities (Map)
 *                                                                 └── resources (scores, kills, etc.)
 *   BattleWorld.finish() → BattleReport
 * </pre>
 */
@Slf4j
public class BattleWorld {

    private final ReplayMeta meta;
    private final Version version;
    private final List<MetaPlayer> metaPlayers;

    // ── Entity state ───────────────────────────────────────────────────
    /** entity_id → EntityState */
    final Map<Integer, EntityState> entities = new LinkedHashMap<>();

    // ── Resources ──────────────────────────────────────────────────────
    final List<TeamScore>      teamScores        = new ArrayList<>();
    final List<KillRecord>     killLog           = new ArrayList<>();
    final List<DamageEvent>    damageEvents      = new ArrayList<>();
    final Map<Integer, List<DamageEvent>> damageByAggressor = new LinkedHashMap<>();
    final List<ChatEvent>      chatLog           = new ArrayList<>();
    final List<ConsumableEvent> consumableLog    = new ArrayList<>();
    final List<CapturePointState> capturePoints  = new ArrayList<>();
    final List<BuffZoneState>  buffZones         = new ArrayList<>();
    final List<WeatherZoneState> weatherZones    = new ArrayList<>();
    final List<BuildingState>  buildings         = new ArrayList<>();
    final List<DeadShipRecord> deadShips         = new ArrayList<>();
    final List<CapturedBuff>   capturedBuffs     = new ArrayList<>();
    final Set<String>          entityTypes       = new LinkedHashSet<>();

    // ── Extended resources (Phase 4 ingest) ────────────────────────────
    final List<ArtillerySalvo> firedSalvos         = new ArrayList<>();
    final List<TorpedoRecord>  torpedoes            = new ArrayList<>();
    final List<ShotHitRecord>  shotHits             = new ArrayList<>();
    final List<PlaneRecord>    planeEvents          = new ArrayList<>();
    final Map<Long, PlaneState> activePlanes        = new LinkedHashMap<>();
    final Map<Long, WardState>  activeWards         = new LinkedHashMap<>();
    final List<VoiceLineEvent> voiceLineLog         = new ArrayList<>();
    final List<RibbonEvent>    ribbonLog            = new ArrayList<>();

    String arenaId;
    String mapName;
    long   mapArenaId;
    int    gameMode;
    String matchGroup;
    Integer winningTeam;
    String finishType;
    String matchResult;
    Float  maxDuration;
    Float  playedDuration;
    Float  extraDuration;

    // ── Player mapping ─────────────────────────────────────────────────
    /** entity_id → (db_id, username) */
    final Map<Integer, PlayerLink> entityToPlayer = new LinkedHashMap<>();
    /** db_id → PlayerInfo */
    final Map<Long, PlayerInfo>    players         = new LinkedHashMap<>();
    /** Vehicle entity_id → Avatar entity_id (owner) */
    final Map<Integer, Integer>    vehicleToOwner  = new LinkedHashMap<>();
    /** db_id → entity_id (from arena state) */
    final Map<Long, Integer>       dbToEntity      = new LinkedHashMap<>();

    int cellPlayerCreateCount;
    int vehicleCreateCount;

    // ── Constructor ────────────────────────────────────────────────────

    public BattleWorld(ReplayMeta meta, Version version) {
        this.meta = meta;
        this.version = version;
        this.metaPlayers = new ArrayList<>();

        // Pre-seed players from replay metadata
        var vehicles = meta.vehicles();
        if (vehicles != null) {
            for (var v : vehicles) {
                long dbId = Integer.toUnsignedLong(v.id().value());
                String name = v.name();
                metaPlayers.add(new MetaPlayer(dbId, name, v.relation(), v.shipId().value()));
                players.put(dbId, new PlayerInfo(name, 0, v.relation()));
            }
        }
        log.info("BattleWorld: {} meta players, version={}", metaPlayers.size(), version);
    }

    // ── Process ────────────────────────────────────────────────────────

    /**
     * Process one decoded packet. This is the main entry point called
     * for each packet in the replay stream.
     */
    public void process(DecodedPayload payload, GameClock clock) {
        float elapsed = clock.seconds();

        switch (payload) {
            // ── Arena / Player state ───────────────────────────────────
            case DecodedPayload.OnArenaStateReceivedPayload as -> {
                arenaId = String.valueOf(as.arenaId());
                ingestArenaPlayers(as.playerStates(), as.botStates(), clock);
            }
            case DecodedPayload.NewPlayerSpawnedInBattlePayload ns -> {
                ingestNewPlayers(ns.playerStates(), ns.botStates());
            }

            // ── Entity lifecycle ───────────────────────────────────────
            case DecodedPayload.EntityCreatePayload ec -> {
                entityTypes.add(ec.packet().entityType());
                ingestEntityCreate(ec.packet(), elapsed);
            }
            case DecodedPayload.EntityEnterPayload ee -> {
                // entity_id → space
            }
            case DecodedPayload.EntityLeavePayload el -> {
                var es = entities.get(el.packet().entityId().value());
                if (es != null) es.isAlive = false;
                // Despawn smoke screens and buff zones
                if ("SmokeScreen".equals(es != null ? es.type : null)
                    || "BuffZone".equals(es != null ? es.type : null)) {
                    entities.remove(el.packet().entityId().value());
                }
            }
            case DecodedPayload.BasePlayerCreatePayload bp -> {
                entityTypes.add(bp.packet().entityType());
                ingestBasePlayerCreate(bp.packet());
            }
            case DecodedPayload.CellPlayerCreatePayload cp -> {
                entityTypes.add(cp.packet().entityType());
                ingestCellPlayerCreate(cp.packet());
            }

            // ── Entity properties ──────────────────────────────────────
            case DecodedPayload.PropertyChangePayload pc -> {
                ingestPropertyChange(pc.change(), elapsed);
            }

            // ── Position ───────────────────────────────────────────────
            case DecodedPayload.PositionPayload pos -> {
                ingestPosition(pos.packet());
            }

            // ── Map ────────────────────────────────────────────────────
            case DecodedPayload.MapPayload mp -> {
                mapName = mp.packet().mapName();
                mapArenaId = mp.packet().arenaId();
            }

            // ── Combat ─────────────────────────────────────────────────
            case DecodedPayload.DamageReceivedPayload dr -> {
                for (var a : dr.aggressors()) {
                    int aggAv = vehicleToOwner.getOrDefault(a.aggressor().value(), a.aggressor().value());
                    var ev = new DamageEvent(elapsed, aggAv, dr.victim().value(), a.damage());
                    damageEvents.add(ev);
                    damageByAggressor.computeIfAbsent(aggAv, k -> new ArrayList<>()).add(ev);
                }
            }
            case DecodedPayload.ShipDestroyedPayload sd -> {
                int victimAv = vehicleToOwner.getOrDefault(sd.victim().value(), sd.victim().value());
                int killerAv = vehicleToOwner.getOrDefault(sd.killer().value(), sd.killer().value());
                var kl = entityToPlayer.get(killerAv);
                var vl = entityToPlayer.get(victimAv);
                killLog.add(new KillRecord(elapsed, killerAv, victimAv,
                    kl != null ? kl.dbId : 0, kl != null ? kl.username : "",
                    vl != null ? vl.dbId : 0, vl != null ? vl.username : "",
                    sd.cause()));
                var es = entities.get(sd.victim().value());
                if (es != null) es.isAlive = false;
                deadShips.add(new DeadShipRecord(elapsed, sd.victim().value(),
                    es != null ? es.x : 0, es != null ? es.z : 0));
            }
            // ── Chat ───────────────────────────────────────────────────
            case DecodedPayload.ChatMessagePayload chat -> {
                // 发送者是 args[0] 的账号 ID（与 meta/arena 的 id 字段一致），
                // 不能用接收方 entity_id（即 replay 主视角 Avatar）来归属消息。
                long senderDbId = Integer.toUnsignedLong(chat.senderId().value());
                var pl = players.get(senderDbId);
                chatLog.add(new ChatEvent(elapsed, chat.entityId().value(),
                    senderDbId,
                    pl != null ? pl.username : "account " + senderDbId,
                    chat.audience(), chat.message()));
            }

            // ── Consumable ─────────────────────────────────────────────
            case DecodedPayload.ConsumablePayload cons -> {
                var pl = entityToPlayer.get(cons.entity().value());
                consumableLog.add(new ConsumableEvent(elapsed, cons.entity().value(),
                    pl != null ? pl.dbId : 0, pl != null ? pl.username : "",
                    cons.consumableId(), cons.duration()));
            }

            // ── Battle end ─────────────────────────────────────────────
            case DecodedPayload.BattleEndPayload be -> {
                if (be.winningTeam() != null) winningTeam = be.winningTeam();
                if (be.finishType() != 0) finishType = String.valueOf(be.finishType());
            }

            // ── Battle results ─────────────────────────────────────────
            case DecodedPayload.BattleResultsPayload br -> {
                try {
                    var node = com.wows.replay.JsonMapper.readTree(br.json());
                    if (node.has("matchResult")) matchResult = node.get("matchResult").asText();
                    if (node.has("finishReason") && finishType == null)
                        finishType = node.get("finishReason").asText();
                } catch (Exception ignored) {}
            }

            // ── Match state / property updates ─────────────────────────
            case DecodedPayload.PropertyUpdatePayload pu -> {
                ingestPropertyUpdate(pu.packet(), elapsed);
            }

            // ── Position (non-volatile, player orientation) ────────────
            case DecodedPayload.PlayerOrientationPayload po -> {
                int eid = po.packet().entityId().value();
                var es = getOrCreateEntity(eid, null);
                es.x = po.packet().position().x();
                es.y = po.packet().position().y();
                es.z = po.packet().position().z();
            }
            case DecodedPayload.NonVolatilePositionPayload nvp -> {
                int eid = nvp.packet().entityId().value();
                var es = getOrCreateEntity(eid, null);
                es.x = nvp.packet().position().x();
                es.y = nvp.packet().position().y();
                es.z = nvp.packet().position().z();
            }

            // ── Artillery / Torpedo events (record for later analysis) ──
            case DecodedPayload.ArtilleryShotsPayload asp -> {
                for (var salvo : asp.salvos()) {
                    firedSalvos.add(new ArtillerySalvo(elapsed, salvo));
                }
                var ownerEid = asp.avatarId().value();
                var es = entities.get(ownerEid);
                if (es != null) es.shotsFired += asp.salvos().stream().mapToLong(s -> s.shots().size()).sum();
            }
            case DecodedPayload.TorpedoesReceivedPayload trp -> {
                for (var td : trp.torpedoes()) {
                    torpedoes.add(new TorpedoRecord(elapsed, td));
                }
            }
            case DecodedPayload.ShotKillsPayload skp -> {
                for (var hit : skp.hits()) {
                    shotHits.add(new ShotHitRecord(elapsed, skp.avatarId(), hit));
                }
            }
            case DecodedPayload.TorpedoDirectionPayload tdp -> {
                // Update matching torpedo's maneuver flag
                for (var t : torpedoes) {
                    if (t.data.shotId() == tdp.shotId() && t.data.ownerId().value() == tdp.ownerId().value()) {
                        torpedoes.set(torpedoes.indexOf(t), t.withManeuver(tdp.targetYaw(), tdp.speedCoef()));
                        break;
                    }
                }
            }

            // ── Gun sync / Ammo ────────────────────────────────────────
            case DecodedPayload.GunSyncPayload gsp -> {
                var es = entities.get(gsp.entityId().value());
                if (es != null) es.turrets.put(gsp.gunId(), new float[]{gsp.yaw(), gsp.pitch()});
            }
            case DecodedPayload.SetAmmoForWeaponPayload saw -> {
                var es = entities.get(saw.entityId().value());
                if (es != null) es.ammoByWeapon.put(saw.weaponType(), saw.ammoParamId().value());
            }

            // ── Aviation ────────────────────────────────────────────────
            case DecodedPayload.PlaneAddedPayload pap -> {
                var ps = new PlaneState(pap.planeId(), pap.entityId().value(), pap.teamId(),
                    pap.paramsId(), pap.x(), pap.z(), elapsed, elapsed);
                activePlanes.put(pap.planeId(), ps);
                planeEvents.add(new PlaneRecord(elapsed, "added", pap.planeId(), ps));
            }
            case DecodedPayload.PlaneRemovedPayload prp -> {
                var ps = activePlanes.remove(prp.planeId());
                planeEvents.add(new PlaneRecord(elapsed, "removed", prp.planeId(), ps));
            }
            case DecodedPayload.PlanePositionPayload ppp -> {
                var ps = activePlanes.get(ppp.planeId());
                if (ps != null) {
                    activePlanes.put(ppp.planeId(), ps.withPosition(ppp.x(), ppp.z(), elapsed));
                }
            }
            case DecodedPayload.WardAddedPayload wap -> {
                activeWards.put(wap.planeId(), new WardState(wap.planeId(), wap.entityId(), wap.ownerId(),
                    wap.position(), wap.radius(), elapsed));
            }
            case DecodedPayload.WardRemovedPayload wrp -> {
                activeWards.remove(wrp.planeId());
            }

            // ── Self damage stats ───────────────────────────────────────
            case DecodedPayload.DamageStatPayload dsp -> {
                // Damage stats are per-avatar; accumulate on the avatar entity
                // (stored on the first entity we find with matching type "Avatar")
            }

            // ── Minimap ─────────────────────────────────────────────────
            case DecodedPayload.MinimapUpdatePayload mup -> {
                for (var entry : mup.updates()) {
                    var es = entities.get(entry.entityId().value());
                    if (es != null) es.isInvisible = !entry.visible();
                }
            }

            // ── Ribbon ──────────────────────────────────────────────────
            case DecodedPayload.RibbonPayload rp -> {
                ribbonLog.add(new RibbonEvent(elapsed, rp.ribbonId()));
            }

            // ── Voice line ───────────────────────────────────────────────
            case DecodedPayload.VoiceLinePayload vl -> {
                voiceLineLog.add(new VoiceLineEvent(elapsed, vl.senderId(), vl.isGlobal(), vl.message()));
            }

            // ── Ignored (for now) ──────────────────────────────────────
            default -> { /* pass-through for unhandled variants */ }
        }
    }

    // ── Ingest: Arena players ──────────────────────────────────────────

    private void ingestArenaPlayers(List<PlayerStateData> playerStates,
                                    List<PlayerStateData> botStates,
                                    GameClock clock) {
        for (var psd : playerStates) {
            ingestOneArenaPlayer(psd, false);
        }
        for (var psd : botStates) {
            ingestOneArenaPlayer(psd, true);
        }
        log.info("ArenaState: {} players + {} bots → {} entity→player mappings, {} players",
            playerStates.size(), botStates.size(), entityToPlayer.size(), players.size());
    }

    private void ingestOneArenaPlayer(PlayerStateData psd, boolean isBot) {
        int entityId = psd.entityId();
        long dbId = psd.dbId();
        if (entityId <= 0 || dbId <= 0) return;

        // Map entity → player
        entityToPlayer.put(entityId, new PlayerLink(dbId, psd.username()));

        // Update or create player info
        var existing = players.get(dbId);
        if (existing != null) {
            existing.entityId = entityId;
            existing.teamId = (int) psd.teamId();
        } else {
            var pi = new PlayerInfo(psd.username(), entityId, 0); // relation unknown for bots
            pi.teamId = (int) psd.teamId();
            players.put(dbId, pi);
        }
        dbToEntity.put(dbId, entityId);

        // Create entity components from arena state
        var es = getOrCreateEntity(entityId, "Avatar");
        es.maxHealth = psd.maxHealth();
        es.health    = psd.maxHealth(); // seed full HP from arena state
        es.teamId    = (int) psd.teamId();
        es.isBot     = isBot;
        es.dbId      = dbId;
        es.playerName = psd.username();

        // Match meta player by metaShipId → get relation
        for (var mp : metaPlayers) {
            if (mp.dbId == dbId) {
                es.relation = mp.relation;
                break;
            }
        }
    }

    private void ingestNewPlayers(List<PlayerStateData> players, List<PlayerStateData> bots) {
        for (var psd : players) ingestOneArenaPlayer(psd, false);
        for (var psd : bots)    ingestOneArenaPlayer(psd, true);
    }

    // ── Ingest: EntityCreate ───────────────────────────────────────────

    private void ingestEntityCreate(EntityCreatePacket ec, float elapsed) {
        int eid = ec.entityId().value();
        String type = ec.entityType();
        var es = getOrCreateEntity(eid, type);
        es.vehicleId = ec.vehicleId();
        if (ec.position() != null) {
            es.x = ec.position().x();
            es.y = ec.position().y();
            es.z = ec.position().z();
        }

        var props = ec.props();
        if (props == null) return;

        switch (type) {
            case "Vehicle" -> {
                vehicleCreateCount++;
                // Vehicle owner → Avatar mapping
                ArgValue owner = props.get("owner");
                if (owner instanceof ArgValue.IntVal iv) {
                    int ownerEid = (int) iv.value();
                    vehicleToOwner.put(eid, ownerEid);
                    getOrCreateEntity(ownerEid, "Avatar");
                }
                extractHealth(props, eid);
                extractTeam(props, eid);
                // Extract shipConfig if present
                ArgValue sc = props.get("shipConfig");
                if (sc instanceof ArgValue.BlobVal bv) {
                    es.shipConfig = bv.value();
                }
            }
            case "Avatar" -> {
                extractHealth(props, eid);
                extractTeam(props, eid);
                // Link to player by db_id if present
                ArgValue dbIdVal = props.get("accountDBID");
                if (dbIdVal instanceof ArgValue.IntVal iv) {
                    long dbId = iv.value();
                    es.dbId = dbId;
                    es.playerName = players.containsKey(dbId) ? players.get(dbId).username : "";
                    entityToPlayer.putIfAbsent(eid, new PlayerLink(dbId, es.playerName));
                    var pi = players.get(dbId);
                    if (pi != null) pi.entityId = eid;
                }
            }
            case "BattleLogic" -> {
                ingestBattleLogic(props);
            }
            case "InteractiveZone" -> {
                ingestInteractiveZone(eid, props, ec.position());
            }
            case "Building" -> {
                float bx = ec.position() != null ? ec.position().x() : 0;
                float bz = ec.position() != null ? ec.position().z() : 0;
                int teamId = getIntProp(props, "teamId");
                long paramsId = getLongProp(props, "paramsId");
                boolean alive = getBoolProp(props, "isAlive", true);
                buildings.add(new BuildingState(eid, bx, bz, teamId, paramsId, alive));
            }
            case "SmokeScreen" -> {
                float r = getFloatProp(props, "radius");
                es.smokeRadius = r;
            }
            case "WeatherZone", "LocalWeatherZone" -> {
                float wx = ec.position() != null ? ec.position().x() : 0;
                float wz = ec.position() != null ? ec.position().z() : 0;
                float wr = getFloatProp(props, "radius");
                long wparams = getLongProp(props, "paramsId");
                // Decode name from byte array
                String wname = decodeName(props.get("name"));
                weatherZones.add(new WeatherZoneState(wname, wx, wz, wr, wparams, eid));
            }
            case "BuffZone" -> {
                float bfx = ec.position() != null ? ec.position().x() : 0;
                float bfz = ec.position() != null ? ec.position().z() : 0;
                float bfr = getFloatProp(props, "radius");
                int bfTeam = getIntProp(props, "teamId");
                boolean bfActive = getBoolProp(props, "isActive", true);
                buffZones.add(new BuffZoneState(eid, bfx, bfz, bfr, bfTeam, bfActive, null));
            }
        }
    }

    private void ingestBattleLogic(Map<String, ArgValue> props) {
        ArgValue state = props.get("state");
        if (!(state instanceof ArgValue.DictVal sd)) return;

        // Team scores
        ArgValue missions = sd.entries().get("missions");
        if (missions instanceof ArgValue.DictVal md) {
            ArgValue ts = md.entries().get("teamsScore");
            if (ts instanceof ArgValue.ArrayVal arr) {
                for (int i = 0; i < arr.elements().size(); i++) {
                    ArgValue entry = arr.elements().get(i);
                    if (entry instanceof ArgValue.DictVal ed) {
                        ArgValue score = ed.entries().get("score");
                        if (score instanceof ArgValue.IntVal sv) {
                            ensureTeamScore(i);
                            teamScores.set(i, new TeamScore(i, sv.value()));
                        }
                    }
                }
            }

            // Scoring rules
            long winScore = md.entries().get("teamWinScore") instanceof ArgValue.IntVal iv ? iv.value() : 1000;
            // Store scoring rules for later use
        }

        // Weather zones seeded from BattleLogic state
        ArgValue weather = sd.entries().get("weather");
        if (weather instanceof ArgValue.DictVal wd) {
            ArgValue localWeather = wd.entries().get("localWeather");
            if (localWeather instanceof ArgValue.ArrayVal lwArr) {
                for (var lwVal : lwArr.elements()) {
                    if (lwVal instanceof ArgValue.DictVal lwd) {
                        var lw = lwd.entries();
                        String name = decodeName(lw.get("name"));
                        float wx = 0, wz = 0, wr = 0;
                        ArgValue pos = lw.get("position");
                        if (pos instanceof ArgValue.Vec2Val v2) { wx = v2.x(); wz = v2.y(); }
                        else if (pos instanceof ArgValue.ArrayVal pa && pa.elements().size() >= 2) {
                            wx = floatFromArg(pa.elements().get(0));
                            wz = floatFromArg(pa.elements().get(1));
                        }
                        ArgValue rad = lw.get("radius");
                        if (rad instanceof ArgValue.FloatVal fv) wr = (float) fv.value();
                        long paramsId = lw.get("paramsId") instanceof ArgValue.IntVal piv ? piv.value() : 0;
                        weatherZones.add(new WeatherZoneState(name, wx, wz, wr, paramsId, null));
                    }
                }
            }
        }
    }

    private void ingestInteractiveZone(int eid, Map<String, ArgValue> props, Vec3 position) {
        float px = position != null ? position.x() : 0;
        float pz = position != null ? position.z() : 0;
        float radius = getFloatProp(props, "radius");
        int teamId = getIntProp(props, "teamId");

        ArgValue cs = props.get("componentsState");
        if (cs instanceof ArgValue.DictVal csd) {
            ArgValue cp = csd.entries().get("controlPoint");
            if (cp instanceof ArgValue.DictVal cpd) {
                var d = cpd.entries();
                int idx = d.get("index") instanceof ArgValue.IntVal iv ? (int) iv.value() : capturePoints.size();
                var cpState = new CapturePointState();
                cpState.index = idx;
                cpState.teamId = teamId;
                cpState.position = new float[]{px, pz};
                cpState.radius = radius;

                ArgValue cl = csd.entries().get("captureLogic");
                if (cl instanceof ArgValue.DictVal cld) {
                    applyCpDict(cpState, cld.entries());
                }
                ensureCpIndex(idx);
                capturePoints.set(idx, cpState);
            } else {
                // Buff zone
                boolean active = getBoolProp(props, "isActive", true);
                buffZones.add(new BuffZoneState(eid, px, pz, radius, teamId, active, null));
            }
        }
    }

    private int bpCount;

    private void ingestBasePlayerCreate(BasePlayerCreatePacket bp) {
        int eid = bp.entityId().value();
        getOrCreateEntity(eid, bp.entityType());

        var props = bp.props();
        if (props != null) {
            // Log first BasePlayerCreate to see actual prop names and componentData
            if (bpCount++ == 0) {
                log.info("First BasePlayerCreate eid={} type={} props={} componentData={}bytes",
                    eid, bp.entityType(),
                    props.keySet(),
                    bp.componentData() != null ? bp.componentData().length : 0);
            }

            // Try to link to player by db_id
            for (String key : props.keySet()) {
                if (key.toLowerCase().contains("dbid") || key.toLowerCase().contains("account")
                    || key.toLowerCase().contains("playerid")) {
                    if (props.get(key) instanceof ArgValue.IntVal iv) {
                        long dbId = iv.value();
                        var es = getOrCreateEntity(eid, null);
                        es.dbId = dbId;
                        // Find player name from meta
                        for (var mp : metaPlayers) {
                            if (mp.dbId == dbId) {
                                es.playerName = mp.name;
                                es.relation = mp.relation;
                                entityToPlayer.put(eid, new PlayerLink(dbId, mp.name));
                                var pi = players.get(dbId);
                                if (pi != null) pi.entityId = eid;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    private void ingestCellPlayerCreate(CellPlayerCreatePacket cp) {
        int eid = cp.entityId().value();
        getOrCreateEntity(eid, cp.entityType());
        var es = entities.get(eid);
        if (es != null) es.vehicleId = cp.vehicleId();

        String type = cp.entityType();
        if ("Avatar".equals(type)) {
            cellPlayerCreateCount++;
            // Match to recording player (relation=0)
            for (var mp : metaPlayers) {
                if (mp.relation == 0 && !entityToPlayer.containsKey(eid)) {
                    entityToPlayer.put(eid, new PlayerLink(mp.dbId, mp.name));
                    var pi = players.get(mp.dbId);
                    if (pi != null) pi.entityId = eid;
                    if (es != null) {
                        es.dbId = mp.dbId;
                        es.playerName = mp.name;
                        es.relation = 0;
                    }
                    break;
                }
            }
        }

        // Extract health/team from internal properties
        extractHealth(cp.props(), eid);
        extractTeam(cp.props(), eid);
    }

    private void ingestPropertyChange(PropertyDecoder.PropertyChange pc, float elapsed) {
        int eid = pc.entityId().value();
        ArgValue val = pc.value();

        switch (pc.kind()) {
            case HEALTH -> { var es = getOrCreateEntity(eid, null); es.health = floatFromArg(val); }
            case MAX_HEALTH -> { var es = getOrCreateEntity(eid, null); es.maxHealth = floatFromArg(val); }
            case TEAM_ID -> {
                int tid = intFromArg(val);
                getOrCreateEntity(eid, null).teamId = tid;
                var pl = entityToPlayer.get(eid);
                if (pl != null) {
                    var pi = players.get(pl.dbId);
                    if (pi != null) pi.teamId = tid;
                }
            }
            case IS_ALIVE -> getOrCreateEntity(eid, null).isAlive = intFromArg(val) != 0;
            case IS_INVISIBLE -> getOrCreateEntity(eid, null).isInvisible = intFromArg(val) != 0;
            case MAX_DURATION -> { if (val instanceof ArgValue.FloatVal fv) maxDuration = (float) fv.value(); }
            case PLAYED_DURATION -> { if (val instanceof ArgValue.FloatVal fv) playedDuration = (float) fv.value(); }
            case EXTRA_DURATION -> { if (val instanceof ArgValue.FloatVal fv) extraDuration = (float) fv.value(); }
            case FINISH_TYPE -> { if (val instanceof ArgValue.StrVal sv) finishType = sv.value(); }
            case MATCH_RESULT -> { if (val instanceof ArgValue.StrVal sv) matchResult = sv.value(); }
            case STATE -> traverseStateDict(eid, val, elapsed);
            case SHIP_CONFIG, VEHICLE_ID, OWNER_ID, OTHER -> { /* recorded but not yet handled */ }
        }
    }

    /** Traverse nested state dict for team scores, control points, weather updates. */
    private void traverseStateDict(int eid, ArgValue val, float elapsed) {
        if (!(val instanceof ArgValue.DictVal sd)) return;

        // state.missions.teamsScore
        ArgValue missions = sd.entries().get("missions");
        if (missions instanceof ArgValue.DictVal md) {
            ArgValue ts = md.entries().get("teamsScore");
            if (ts instanceof ArgValue.ArrayVal arr) {
                for (int i = 0; i < arr.elements().size(); i++) {
                    ArgValue entry = arr.elements().get(i);
                    if (entry instanceof ArgValue.DictVal ed) {
                        ArgValue score = ed.entries().get("score");
                        if (score instanceof ArgValue.IntVal sv) {
                            ensureTeamScore(i);
                            teamScores.set(i, new TeamScore(i, sv.value()));
                        }
                    }
                }
            }
        }

        // state.controlPoints
        ArgValue cps = sd.entries().get("controlPoints");
        if (cps instanceof ArgValue.ArrayVal cpArr) {
            for (int i = 0; i < cpArr.elements().size(); i++) {
                ensureCpIndex(i);
                var cp = capturePoints.get(i);
                ArgValue cpEntry = cpArr.elements().get(i);
                if (cpEntry instanceof ArgValue.DictVal cpd) {
                    applyCpDict(cp, cpd.entries());
                }
            }
        }

        // state.weather.localWeather
        ArgValue weather = sd.entries().get("weather");
        if (weather instanceof ArgValue.DictVal wd) {
            ArgValue localWeather = wd.entries().get("localWeather");
            if (localWeather instanceof ArgValue.ArrayVal lwArr) {
                for (int i = 0; i < lwArr.elements().size(); i++) {
                    ArgValue lwVal = lwArr.elements().get(i);
                    if (lwVal instanceof ArgValue.DictVal lwd) {
                        var lw = lwd.entries();
                        String name = decodeName(lw.get("name"));
                        float wx = 0, wz = 0, wr = 0;
                        ArgValue pos = lw.get("position");
                        if (pos instanceof ArgValue.Vec2Val v2) { wx = v2.x(); wz = v2.y(); }
                        else if (pos instanceof ArgValue.ArrayVal pa && pa.elements().size() >= 2) {
                            wx = floatFromArg(pa.elements().get(0));
                            wz = floatFromArg(pa.elements().get(1));
                        }
                        ArgValue rad = lw.get("radius");
                        if (rad instanceof ArgValue.FloatVal fv) wr = (float) fv.value();
                        long paramsId = lw.get("paramsId") instanceof ArgValue.IntVal piv ? piv.value() : 0;
                        while (weatherZones.size() <= i) {
                            weatherZones.add(new WeatherZoneState("", 0, 0, 0, 0, null));
                        }
                        weatherZones.set(i, new WeatherZoneState(name, wx, wz, wr, paramsId, null));
                    }
                }
            }
        }
    }

    private void ingestPosition(PositionPacket pos) {
        int eid = pos.entityId().value();
        var es = getOrCreateEntity(eid, null);
        es.x = pos.position().x();
        es.y = pos.position().y();
        es.z = pos.position().z();
        es.heading = pos.rotation().yaw();
    }

    // ── Ingest: PropertyUpdate ─────────────────────────────────────────

    private void ingestPropertyUpdate(PropertyUpdatePacket pu, float elapsed) {
        // updateCmd is byte[] from Parser — try to decode it
        byte[] raw = null;
        if (pu.updateCmd() instanceof byte[] b) {
            raw = b;
        } else {
            return;
        }

        // Only try PickleReader if the data looks like pickle
        // (valid pickle starts with 0x80, '(', ']', 'K', 'J', 'M', 'U', 'T', 'S', 'N', 'G', 'F', 'I', '{', or '}')
        if (raw.length == 0) return;
        int first = raw[0] & 0xFF;
        if (first != 0x80 && first != '(' && first != ']' && first != 'K'
            && first != 'J' && first != 'M' && first != 'U' && first != 'T'
            && first != 'S' && first != 'N' && first != 'G' && first != 'F'
            && first != 'I' && first != '{' && first != '}' && first != '.'
            && first != 'e' && first != 'a' && first != 't' && first != 'r'
            && first != 'u' && first != 'q' && first != 'h' && first != '0') {
            return;
        }

        Object decoded;
        try {
            decoded = PickleReader.decode(raw);
        } catch (Exception e) {
            log.debug("PropertyUpdate pickle decode failed: {}", e.getMessage());
            return;
        }
        if (decoded == null) return;

        // Handle known patterns
        if ("state".equals(pu.property())) {
            ingestStatePropertyUpdate(decoded, elapsed);
        } else if ("points".equals(pu.property())) {
            ingestSmokePointsUpdate(pu.entityId().value(), decoded);
        } else if ("componentsState".equals(pu.property())) {
            ingestComponentsStateUpdate(pu.entityId().value(), decoded);
        }
    }

    /** Parse state.missions.teamsScore / state.controlPoints updates. */
    private void ingestStatePropertyUpdate(Object decoded, float elapsed) {
        if (!(decoded instanceof Map<?, ?> root)) return;

        // { levels: [...], action: {...} }
        Object levelsObj = root.get("levels");
        Object actionObj = root.get("action");

        if (!(levelsObj instanceof List<?> levels) || !(actionObj instanceof Map<?, ?> action)) return;

        // state → missions → teamsScore → [N] → SetKey{score}
        if (levels.size() >= 3
            && "missions".equals(strVal(levels.get(0)))
            && "teamsScore".equals(strVal(levels.get(1)))
            && levels.get(2) instanceof Long teamIdx
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            String key = strVal(action.get("key"));
            if ("score".equals(key)) {
                int idx = teamIdx.intValue();
                Object scoreVal = action.get("value");
                long score = scoreVal instanceof Long l ? l : (scoreVal instanceof Double d ? d.longValue() : 0);
                ensureTeamScore(idx);
                teamScores.set(idx, new TeamScore(idx, score));
            }
        }

        // state → controlPoints → [N] → SetKey{...} (legacy)
        if (levels.size() >= 2
            && "controlPoints".equals(strVal(levels.get(0)))
            && levels.get(1) instanceof Long cpIdx
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            String key = strVal(action.get("key"));
            int idx = cpIdx.intValue();
            ensureCpIndex(idx);
            var cp = capturePoints.get(idx);
            Object val = action.get("value");
            switch (key) {
                case "teamId"       -> cp.teamId = longVal(val);
                case "invaderTeam"  -> cp.invaderTeam = longVal(val);
                case "hasInvaders"  -> cp.hasInvaders = longVal(val) != 0;
                case "bothInside"   -> cp.bothInside = longVal(val) != 0;
                case "isEnabled"    -> cp.isEnabled = longVal(val) != 0;
                case "progress"     -> cp.progress = val instanceof Double d ? d.floatValue() : 0f;
            }
        }

        // state → weather → localWeather → [N] → SetKey
        if (levels.size() >= 3
            && "weather".equals(strVal(levels.get(0)))
            && "localWeather".equals(strVal(levels.get(1)))
            && levels.get(2) instanceof Long wzIdx
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            String key = strVal(action.get("key"));
            Object val = action.get("value");
            int idx = wzIdx.intValue();
            while (weatherZones.size() <= idx) {
                weatherZones.add(new WeatherZoneState("", 0, 0, 0, 0, null));
            }
            var wz = weatherZones.get(idx);
            switch (key) {
                case "position" -> {
                    if (val instanceof List<?> p && p.size() >= 2) {
                        // WeatherZoneState is a record, so replace the entry
                        weatherZones.set(idx, new WeatherZoneState(wz.name(),
                            p.get(0) instanceof Number n ? n.floatValue() : 0,
                            p.get(1) instanceof Number n ? n.floatValue() : 0,
                            wz.radius(), wz.paramsId(), wz.entityId()));
                    }
                }
                case "radius" -> {
                    float r = val instanceof Number n ? n.floatValue() : 0;
                    weatherZones.set(idx, new WeatherZoneState(wz.name(), wz.x(), wz.z(), r, wz.paramsId(), wz.entityId()));
                }
                case "paramsId" -> {
                    long pid = longVal(val);
                    weatherZones.set(idx, new WeatherZoneState(wz.name(), wz.x(), wz.z(), wz.radius(), pid, wz.entityId()));
                }
            }
        }

        // state → missions → teamsScore → [N] → SetRange (initial scores array)
        if (levels.size() >= 3
            && "missions".equals(strVal(levels.get(0)))
            && "teamsScore".equals(strVal(levels.get(1)))
            && levels.get(2) instanceof Long
            && "SetRange".equals(strVal(action.get("_action"))))
        {
            Object valuesObj = action.get("values");
            if (valuesObj instanceof List<?> values) {
                for (int i = 0; i < values.size(); i++) {
                    Object entry = values.get(i);
                    if (entry instanceof Map<?, ?> entryMap) {
                        Object sv = entryMap.get("score");
                        long s = sv instanceof Long l ? l : (sv instanceof Double d ? d.longValue() : 0);
                        ensureTeamScore(i);
                        teamScores.set(i, new TeamScore(i, s));
                    }
                }
            }
        }
    }

    private void ingestSmokePointsUpdate(int entityId, Object decoded) {
        // Handle smoke screen points updates
    }

    private void ingestComponentsStateUpdate(int entityId, Object decoded) {
        if (!(decoded instanceof Map<?, ?> root)) return;
        Object levelsObj = root.get("levels");
        Object actionObj = root.get("action");
        if (!(actionObj instanceof Map<?, ?> action)) return;

        // componentsState → captureLogic → SetKey{...}
        if (levelsObj instanceof List<?> levels
            && levels.size() >= 1
            && "captureLogic".equals(strVal(levels.get(0)))
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            String key = strVal(action.get("key"));
            Object val = action.get("value");
            // Find the InteractiveZone by entity_id and update its CP state
            for (int i = 0; i < capturePoints.size(); i++) {
                var cp = capturePoints.get(i);
                switch (key) {
                    case "hasInvaders"  -> cp.hasInvaders = longVal(val) != 0;
                    case "invaderTeam"  -> cp.invaderTeam = longVal(val);
                    case "progress"     -> cp.progress = val instanceof Double d ? d.floatValue() : 0f;
                    case "bothInside"   -> cp.bothInside = longVal(val) != 0;
                    case "isEnabled"    -> cp.isEnabled = longVal(val) != 0;
                }
            }
        }
    }

    /** Safe string extraction from pickle values. */
    private static String strVal(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return v != null ? v.toString() : "";
    }

    private static long longVal(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        if (v instanceof Number n) return n.longValue();
        return 0;
    }

    // ── Finish ─────────────────────────────────────────────────────────

    /** Called after all packets have been processed. */
    public void finish() {
        log.info("BattleWorld finish: {} entities, {} players, {} kills, {} damage, {} chat, {} consumables",
            entities.size(), players.size(), killLog.size(),
            damageEvents.size(), chatLog.size(), consumableLog.size());
        log.info("  Vehicle Creates: {}, CellPlayer Creates: {}", vehicleCreateCount, cellPlayerCreateCount);
        log.info("  Entity types: {}", entityTypes);
        log.info("  Capture points: {}, Buff zones: {}, Weather zones: {}, Buildings: {}",
            capturePoints.size(), buffZones.size(), weatherZones.size(), buildings.size());
        log.info("  Salvos: {}, Torpedoes: {}, Shot hits: {}, Planes: {}, Wards: {}, Ribbons: {}, Voice lines: {}",
            firedSalvos.size(), torpedoes.size(), shotHits.size(),
            planeEvents.size(), activeWards.size(), ribbonLog.size(), voiceLineLog.size());
    }

    // ── Helpers: Entity management ─────────────────────────────────────

    EntityState getOrCreateEntity(int eid, String type) {
        var e = entities.get(eid);
        if (e == null) {
            e = new EntityState(new EntityId(eid));
            e.type = (type != null ? type : "Unknown");
            entities.put(eid, e);
        } else if (type != null && "Unknown".equals(e.type)) {
            e.type = type;
        }
        return e;
    }

    // ── Helpers: Resources ─────────────────────────────────────────────

    private TeamScore ensureTeamScore(int index) {
        while (teamScores.size() <= index) {
            teamScores.add(new TeamScore(teamScores.size(), 0));
        }
        return teamScores.get(index);
    }

    private void ensureCpIndex(int index) {
        while (capturePoints.size() <= index) {
            capturePoints.add(new CapturePointState());
        }
    }

    // ── Helpers: Property extraction ───────────────────────────────────

    private void extractHealth(Map<String, ArgValue> props, int eid) {
        var es = getOrCreateEntity(eid, null);
        ArgValue h = props.get("health");
        if (h instanceof ArgValue.FloatVal fv) es.health = (float) fv.value();
        else if (h instanceof ArgValue.IntVal iv) es.health = (float) iv.value();

        ArgValue mh = props.get("maxHealth");
        if (mh instanceof ArgValue.FloatVal fv) es.maxHealth = (float) fv.value();
        else if (mh instanceof ArgValue.IntVal iv) es.maxHealth = (float) iv.value();

        ArgValue alive = props.get("isAlive");
        if (alive instanceof ArgValue.IntVal iv) es.isAlive = iv.value() != 0;
        else if (alive instanceof ArgValue.BoolVal bv) es.isAlive = bv.value();
    }

    private void extractTeam(Map<String, ArgValue> props, int eid) {
        ArgValue t = props.get("teamId");
        if (t instanceof ArgValue.IntVal iv) {
            getOrCreateEntity(eid, null).teamId = (int) iv.value();
        }
    }

    private void applyCpDict(CapturePointState s, Map<String, ArgValue> dict) {
        ArgValue v;
        v = dict.get("hasInvaders");
        if (v instanceof ArgValue.IntVal iv) s.hasInvaders = iv.value() != 0;
        v = dict.get("invaderTeam");
        if (v instanceof ArgValue.IntVal iv) s.invaderTeam = (int) iv.value();
        v = dict.get("progress");
        if (v instanceof ArgValue.FloatVal fv) s.progress = (float) fv.value();
        else if (v instanceof ArgValue.ArrayVal av && av.elements().size() >= 2) {
            s.progress = floatFromArg(av.elements().get(0));
        }
        v = dict.get("bothInside");
        if (v instanceof ArgValue.IntVal iv) s.bothInside = iv.value() != 0;
        v = dict.get("isEnabled");
        if (v instanceof ArgValue.IntVal iv) s.isEnabled = iv.value() != 0;
    }

    // ── Static helpers ─────────────────────────────────────────────────

    static int intFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> (int) iv.value();
            case ArgValue.FloatVal fv -> (int) fv.value();
            case ArgValue.BoolVal bv -> bv.value() ? 1 : 0;
            case null, default -> 0;
        };
    }

    static float floatFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.FloatVal fv -> (float) fv.value();
            case ArgValue.IntVal iv -> (float) iv.value();
            case null, default -> 0f;
        };
    }

    static int getIntProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v != null ? intFromArg(v) : 0;
    }

    static long getLongProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v instanceof ArgValue.IntVal iv ? iv.value() : 0;
    }

    static float getFloatProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v != null ? floatFromArg(v) : 0f;
    }

    static boolean getBoolProp(Map<String, ArgValue> props, String key, boolean def) {
        ArgValue v = props.get(key);
        if (v instanceof ArgValue.BoolVal bv) return bv.value();
        if (v instanceof ArgValue.IntVal iv) return iv.value() != 0;
        return def;
    }

    static String decodeName(ArgValue val) {
        return switch (val) {
            case ArgValue.ArrayVal arr -> {
                byte[] bytes = new byte[arr.elements().size()];
                for (int i = 0; i < arr.elements().size(); i++) {
                    bytes[i] = (byte) intFromArg(arr.elements().get(i));
                }
                yield new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            case ArgValue.StrVal sv -> sv.value();
            case null, default -> "";
        };
    }

    // ── Inner types ────────────────────────────────────────────────────

    public record PlayerLink(long dbId, String username) {}

    public static class PlayerInfo {
        public String username;
        public int entityId;
        public int teamId = -1;
        public int relation;
        public PlayerInfo(String u, int e, int r) { username = u; entityId = e; relation = r; }
    }

    public record MetaPlayer(long dbId, String name, int relation, long shipId) {}

    // ── Resource types ─────────────────────────────────────────────────

    public record TeamScore(int teamIndex, long score) {}

    public record KillRecord(float clock, int killerEid, int victimEid,
                              long killerDbId, String killerName,
                              long victimDbId, String victimName, int cause) {}

    public record DamageEvent(float clock, int aggressorId, int victimId, float amount) {}

    public record ChatEvent(float clock, int entityId, long dbId,
                             String senderName, String channel, String message) {}

    public record ConsumableEvent(float clock, int entityId, long dbId,
                                   String username, long consumableId, float duration) {}

    public record DeadShipRecord(float clock, int victimId, float x, float z) {}

    public record CapturedBuff(int entityId, long paramsId, int capturedBy, float clock) {}

    public static class CapturePointState {
        public int index;
        public long teamId = -1;
        public long invaderTeam = -1;
        public float progress;
        public boolean hasInvaders;
        public boolean bothInside;
        public boolean isEnabled = true;
        public float[] position;
        public float radius;
    }

    public record BuffZoneState(int entityId, float x, float z, float radius,
                                 int teamId, boolean isActive, Long dropParamsId) {}

    public record WeatherZoneState(String name, float x, float z, float radius,
                                    long paramsId, Integer entityId) {}

    public record BuildingState(int entityId, float x, float z, int teamId,
                                 long paramsId, boolean isAlive) {}

    // ── Extended resource types (Phase 4) ──────────────────────────────

    /** Artillery salvo wrapped with clock for minimap output. */
    public record ArtillerySalvo(float clock, com.wows.replay.decode.DecodedPayload.ArtillerySalvo salvo) {}

    /** Torpedo record with optional maneuver data. */
    public record TorpedoRecord(float clock, com.wows.replay.decode.DecodedPayload.TorpedoData data,
                                 boolean hasManeuver, float targetYaw, float speedCoef) {
        public TorpedoRecord(float clock, com.wows.replay.decode.DecodedPayload.TorpedoData data) {
            this(clock, data, false, 0f, 0f);
        }
        public TorpedoRecord withManeuver(float yaw, float coef) {
            return new TorpedoRecord(clock, data, true, yaw, coef);
        }
    }

    public record ShotHitRecord(float clock, com.wows.replay.model.AvatarId avatarId,
                                 com.wows.replay.decode.DecodedPayload.ShotHitEntry hit) {}

    public record PlaneState(long planeId, int ownerEntityId, int teamId,
                              com.wows.replay.model.GameParamId paramsId,
                              float x, float z, float addedAt, float lastUpdateAt) {
        public PlaneState withPosition(float nx, float nz, float t) {
            return new PlaneState(planeId, ownerEntityId, teamId, paramsId, nx, nz, addedAt, t);
        }
    }

    public record PlaneRecord(float clock, String action, long planeId, PlaneState state) {}

    public record WardState(long wardId, com.wows.replay.model.EntityId entityId,
                             com.wows.replay.model.EntityId ownerId,
                             com.wows.replay.model.Vec3 position,
                             float radius, float addedAt) {}

    public record VoiceLineEvent(float clock, com.wows.replay.model.AccountId senderId,
                                  boolean isGlobal, String message) {}

    public record RibbonEvent(float clock, int ribbonId) {}
}
