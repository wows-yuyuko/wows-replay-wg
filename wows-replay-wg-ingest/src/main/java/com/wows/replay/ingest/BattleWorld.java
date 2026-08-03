package com.wows.replay.ingest;

import com.wows.replay.ReplayMeta;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.decode.PlayerStateData;
import com.wows.replay.decode.PropertyDecoder;
import com.wows.replay.spi.GameConstantsProvider;
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
 * and produces a {@link com.wows.replay.ingest.report.BattleReport} on finish.</p>
 *
 * <p>Architecture:
 * <pre>
 *   Parser → Packet → PacketDecoder → DecodedPayload → BattleWorld.process()
 *                                                                 ├── entities (Map)
 *                                                                 └── resources (scores, kills, etc.)
 *   BattleWorld.finish() → com.wows.replay.ingest.report.BattleReport
 * </pre>
 */
@Slf4j
public class BattleWorld {

    private final ReplayMeta meta;
    private final Version version;
    private final List<MetaPlayer> metaPlayers;
    /** 游戏常量查询（消耗品/战斗阶段/模式名），无注入时兜底为空实现（§12.4.3）。 */
    private final GameConstantsProvider constants;
    /** 当前推进时钟（§12.4.4），由 process() 按规则更新。 */
    private GameClock currentClock = GameClock.ZERO;

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
    /** Active buff zones keyed by entity id (despawned on EntityLeave, mirrors Rust). */
    final Map<Integer, BuffZoneState> buffZones  = new LinkedHashMap<>();
    final List<WeatherZoneState> weatherZones    = new ArrayList<>();
    final List<BuildingState>  buildings         = new ArrayList<>();
    final List<DeadShipRecord> deadShips         = new ArrayList<>();
    final List<CapturedBuff>   capturedBuffs     = new ArrayList<>();
    final Set<String>          entityTypes       = new LinkedHashSet<>();

    // ── Extended resources (Phase 4 ingest) ────────────────────────────
    final List<ArtillerySalvo> firedSalvos         = new ArrayList<>();
    final List<TorpedoRecord>  torpedoes            = new ArrayList<>();
    /** 在飞鱼雷（命中时移除），对标 Rust ActiveTorpedoOrder */
    final Map<Long, TorpedoRecord> activeTorpedoes  = new LinkedHashMap<>();
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
    Float  battleStartClock;
    Float  battleResultClock;
    Float  battleEndClock;
    /** 收到 BattleEnd 置 true（匹配 report.rs MatchState.match_finished）。 */
    boolean matchFinished;
    /** finishType 原始 int（battle.xml FINISH_TYPE id）。 */
    int finishTypeId;
    /** 0x22 BattleResults 原始 JSON 字符串。 */
    String battleResultsJson;
    /** receiveDamageStat 累积（服务端权威的自我玩家按武器伤害）。 */
    final List<com.wows.replay.ingest.report.DamageStatEntry> selfDamageStats = new ArrayList<>();
    /** BattleLogic timeLeft 属性（秒），minimap frame 用 */
    Float  timeLeft;
    /** BattleLogic battleStage 属性 id（BATTLE_STAGES：0=Waiting,1=Battle,2=Results,3=Finishing,4=Ended） */
    Integer battleStageId;
    /** 存活烟幕（EntityLeave 时移除），minimap frame 用 */
    final Map<Integer, EntityState> smokeScreens = new LinkedHashMap<>();
    // ── 计分规则（BattleLogic state.missions.hold，minimap scoring_rules 用）────
    long teamWinScore;
    long holdReward;
    float holdPeriod;
    final List<Integer> holdCpIndices = new ArrayList<>();

    // ── Player mapping ─────────────────────────────────────────────────
    /** entity_id → (db_id, username) */
    final Map<Integer, PlayerLink> entityToPlayer = new LinkedHashMap<>();
    /** db_id → PlayerInfo */
    final Map<Long, PlayerInfo>    players         = new LinkedHashMap<>();
    /** db_id → 竞技场名册原始状态（dumper 输出 initial_state 用） */
    final Map<Long, com.wows.replay.decode.PlayerStateData> arenaPlayers = new LinkedHashMap<>();
    /** Vehicle entity_id → Avatar entity_id (owner) */
    final Map<Integer, Integer>    vehicleToOwner  = new LinkedHashMap<>();
    /** db_id → entity_id (from arena state) */
    final Map<Long, Integer>       dbToEntity      = new LinkedHashMap<>();

    int cellPlayerCreateCount;
    int vehicleCreateCount;

    // ── Constructor ────────────────────────────────────────────────────

    public BattleWorld(ReplayMeta meta, Version version) {
        this(meta, version, null);
    }

    /**
     * @param constants 游戏常量查询；可为 null，内部兜底为 {@link GameConstantsProvider#empty()}
     *                 （§12.4.3：无 GameConstants 用默认实现，而不是不喂常量）。
     */
    public BattleWorld(ReplayMeta meta, Version version, GameConstantsProvider constants) {
        this.meta = meta;
        this.version = version;
        this.constants = constants != null ? constants : GameConstantsProvider.empty();
        this.metaPlayers = new ArrayList<>();
        this.gameMode = meta.gameMode();
        this.matchGroup = meta.matchGroup();
        this.maxDuration = (float) meta.duration();

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
     *
     * <p>Equivalent to Rust {@code ingest::dispatch}: the switch is the single
     * router, each handled variant delegates to a dedicated handler (below) so
     * the compiler keeps the exhaustive sealed-switch safety net.</p>
     */
    public void process(DecodedPayload payload, GameClock clock) {
        // 时钟推进（§12.4.4）：packet.clock > 0 || 当前 clock == 0 才更新，
        // 保证开局前 clock=0 的包不把已推进的时钟倒退回 0。
        if (clock.seconds() > 0f || currentClock.seconds() == 0f) {
            currentClock = clock;
        }
        float elapsed = currentClock.seconds();

        switch (payload) {
            // ── Arena / Player state ───────────────────────────────────
            case DecodedPayload.OnArenaStateReceivedPayload as -> handleArenaStateReceived(as, currentClock);
            case DecodedPayload.NewPlayerSpawnedInBattlePayload ns -> handleNewPlayerSpawned(ns);

            // ── Entity lifecycle ───────────────────────────────────────
            case DecodedPayload.EntityCreatePayload ec -> handleEntityCreate(ec, elapsed);
            case DecodedPayload.EntityLeavePayload el -> handleEntityLeave(el);
            case DecodedPayload.BasePlayerCreatePayload bp -> handleBasePlayerCreate(bp);
            case DecodedPayload.CellPlayerCreatePayload cp -> handleCellPlayerCreate(cp);

            // ── Entity properties / position ───────────────────────────
            case DecodedPayload.PropertyChangePayload pc -> handlePropertyChange(pc, elapsed);
            case DecodedPayload.PropertyUpdatePayload pu -> handlePropertyUpdate(pu, elapsed);
            case DecodedPayload.PositionPayload pos -> handlePosition(pos);

            // ── Map ────────────────────────────────────────────────────
            case DecodedPayload.MapPayload mp -> handleMap(mp);

            // ── Combat ─────────────────────────────────────────────────
            case DecodedPayload.DamageReceivedPayload dr -> handleDamageReceived(dr, elapsed);
            case DecodedPayload.ShipDestroyedPayload sd -> handleShipDestroyed(sd, elapsed);

            // ── Chat / Consumable ──────────────────────────────────────
            case DecodedPayload.ChatMessagePayload chat -> handleChat(chat, elapsed);
            case DecodedPayload.ConsumablePayload cons -> handleConsumable(cons, elapsed);

            // ── Battle end / results ───────────────────────────────────
            case DecodedPayload.BattleEndPayload be -> handleBattleEnd(be, elapsed);
            case DecodedPayload.BattleResultsPayload br -> handleBattleResults(br);

            // ── Position (non-volatile, player orientation) ────────────
            case DecodedPayload.PlayerOrientationPayload po -> handlePlayerOrientation(po);
            case DecodedPayload.NonVolatilePositionPayload nvp -> handleNonVolatilePosition(nvp);

            // ── Artillery / Torpedo events ─────────────────────────────
            case DecodedPayload.ArtilleryShotsPayload asp -> handleArtilleryShots(asp, elapsed);
            case DecodedPayload.TorpedoesReceivedPayload trp -> handleTorpedoesReceived(trp, elapsed);
            case DecodedPayload.ShotKillsPayload skp -> handleShotKills(skp, elapsed);
            case DecodedPayload.TorpedoDirectionPayload tdp -> handleTorpedoDirection(tdp);

            // ── Gun sync / Ammo ────────────────────────────────────────
            case DecodedPayload.GunSyncPayload gsp -> handleGunSync(gsp);
            case DecodedPayload.SetAmmoForWeaponPayload saw -> handleSetAmmo(saw);

            // ── Aviation ───────────────────────────────────────────────
            case DecodedPayload.PlaneAddedPayload pap -> handlePlaneAdded(pap, elapsed);
            case DecodedPayload.PlaneRemovedPayload prp -> handlePlaneRemoved(prp, elapsed);
            case DecodedPayload.PlanePositionPayload ppp -> handlePlanePosition(ppp, elapsed);
            case DecodedPayload.WardAddedPayload wap -> handleWardAdded(wap, elapsed);
            case DecodedPayload.WardRemovedPayload wrp -> handleWardRemoved(wrp);

            // ── Self damage stats ──────────────────────────────────────
            case DecodedPayload.DamageStatPayload dsp -> handleDamageStat(dsp);

            // ── Minimap / Ribbon / Voice ───────────────────────────────
            case DecodedPayload.MinimapUpdatePayload mup -> handleMinimapUpdate(mup, elapsed);
            case DecodedPayload.RibbonPayload rp -> handleRibbon(rp, elapsed);
            case DecodedPayload.VoiceLinePayload vl -> handleVoiceLine(vl, elapsed);

            // ── Ignored by design（不入战报：EntityMethod 直通、EntityEnter/EntityControl、
            //     OnGameRoomStateChanged、Camera/Cruise/OwnShip/ServerTick/ServerTimestamp/
            //     GunMarker/PlayerNetStats/InitFlag/InitMarker/SetWeaponLock/SubController/
            //     ShotTracking/Version/Map（已处理）/Unknown/Invalid）──────────
            default -> { /* pass-through for unhandled variants */ }
        }
    }

    // ── 分发 handlers（对标 Rust ingest::dispatch 各 handler 模块）──────────

    private void handleArenaStateReceived(DecodedPayload.OnArenaStateReceivedPayload as, GameClock clock) {
        // 保留首个 arena id（onWorldStateReceived 等后续包 arenaId=0，不覆盖）
        if (arenaId == null && as.arenaId() != 0) {
            arenaId = String.valueOf(as.arenaId());
        }
        ingestArenaPlayers(as.playerStates(), as.botStates(), clock);
    }

    private void handleNewPlayerSpawned(DecodedPayload.NewPlayerSpawnedInBattlePayload ns) {
        ingestNewPlayers(ns.playerStates(), ns.botStates());
    }

    private void handleEntityCreate(DecodedPayload.EntityCreatePayload ec, float elapsed) {
        entityTypes.add(ec.packet().entityType());
        ingestEntityCreate(ec.packet(), elapsed);
    }

    private void handleEntityLeave(DecodedPayload.EntityLeavePayload el) {
        int eid = el.packet().entityId().value();
        var es = entities.get(eid);
        if (es != null) es.isAlive = false;
        // Despawn smoke screens and buff zones (mirrors Rust despawn policy:
        // buff zones are removed from the active set on EntityLeave)
        if (smokeScreens.remove(eid) != null || buffZones.remove(eid) != null
                || "SmokeScreen".equals(es != null ? es.type : null)) {
            entities.remove(eid);
        }
    }

    private void handleBasePlayerCreate(DecodedPayload.BasePlayerCreatePayload bp) {
        entityTypes.add(bp.packet().entityType());
        ingestBasePlayerCreate(bp.packet());
    }

    private void handleCellPlayerCreate(DecodedPayload.CellPlayerCreatePayload cp) {
        entityTypes.add(cp.packet().entityType());
        ingestCellPlayerCreate(cp.packet());
    }

    private void handlePropertyChange(DecodedPayload.PropertyChangePayload pc, float elapsed) {
        ingestPropertyChange(pc.change(), elapsed);
    }

    private void handlePropertyUpdate(DecodedPayload.PropertyUpdatePayload pu, float elapsed) {
        ingestPropertyUpdate(pu.packet(), elapsed);
    }

    private void handlePosition(DecodedPayload.PositionPayload pos) {
        ingestPosition(pos.packet());
    }

    private void handleMap(DecodedPayload.MapPayload mp) {
        mapName = mp.packet().mapName();
        mapArenaId = mp.packet().arenaId();
    }

    private void handleDamageReceived(DecodedPayload.DamageReceivedPayload dr, float elapsed) {
        for (var a : dr.aggressors()) {
            // 直接用原始 aggressor 实体 id（对标 Rust damage_ledger）
            int agg = a.aggressor().value();
            var ev = new DamageEvent(elapsed, agg, dr.victim().value(), a.damage());
            damageEvents.add(ev);
            damageByAggressor.computeIfAbsent(agg, k -> new ArrayList<>()).add(ev);
        }
    }

    private void handleShipDestroyed(DecodedPayload.ShipDestroyedPayload sd, float elapsed) {
        // 直接用原始实体 id（对标 Rust KillRecord，不再经 vehicleToOwner 翻译）
        int victimEid = sd.victim().value();
        int killerEid = sd.killer().value();
        var kl = entityToPlayer.get(killerEid);
        var vl = entityToPlayer.get(victimEid);
        killLog.add(new KillRecord(elapsed, killerEid, victimEid,
            kl != null ? kl.dbId : 0, kl != null ? kl.username : "",
            vl != null ? vl.dbId : 0, vl != null ? vl.username : "",
            sd.cause()));
        var es = entities.get(sd.victim().value());
        if (es != null) es.isAlive = false;
        deadShips.add(new DeadShipRecord(elapsed, sd.victim().value(),
            es != null ? es.x : 0, es != null ? es.z : 0));
    }

    private void handleChat(DecodedPayload.ChatMessagePayload chat, float elapsed) {
        // 发送者是 args[0] 的账号 ID（与 meta/arena 的 id 字段一致），
        // 不能用接收方 entity_id（即 replay 主视角 Avatar）来归属消息。
        long senderDbId = Integer.toUnsignedLong(chat.senderId().value());
        // System messages carry sender_id 0 and are dropped (mirrors Rust).
        if (senderDbId == 0) return;
        var pl = players.get(senderDbId);
        chatLog.add(new ChatEvent(elapsed, chat.entityId().value(),
            senderDbId,
            pl != null ? pl.username : "account " + senderDbId,
            chat.audience(), chat.message()));
    }

    private void handleConsumable(DecodedPayload.ConsumablePayload cons, float elapsed) {
        var pl = entityToPlayer.get(cons.entity().value());
        consumableLog.add(new ConsumableEvent(elapsed, cons.entity().value(),
            pl != null ? pl.dbId : 0, pl != null ? pl.username : "",
            cons.consumableId(), cons.duration()));
    }

    private void handleBattleEnd(DecodedPayload.BattleEndPayload be, float elapsed) {
        if (be.winningTeam() != null) winningTeam = be.winningTeam();
        if (be.finishType() != 0) {
            finishType = String.valueOf(be.finishType());
            finishTypeId = be.finishType();
        }
        battleEndClock = elapsed;
        matchFinished = true;
    }

    private void handleBattleResults(DecodedPayload.BattleResultsPayload br) {
        battleResultsJson = br.json();
        try {
            var node = com.wows.replay.JsonMapper.readTree(br.json());
            if (node.has("matchResult")) matchResult = node.get("matchResult").asText();
            if (node.has("finishReason") && finishType == null)
                finishType = node.get("finishReason").asText();
        } catch (Exception ignored) {}
    }

    private void handlePlayerOrientation(DecodedPayload.PlayerOrientationPayload po) {
        int eid = po.packet().entityId().value();
        var es = getOrCreateEntity(eid, null);
        es.x = po.packet().position().x();
        es.y = po.packet().position().y();
        es.z = po.packet().position().z();
    }

    private void handleNonVolatilePosition(DecodedPayload.NonVolatilePositionPayload nvp) {
        int eid = nvp.packet().entityId().value();
        var es = getOrCreateEntity(eid, null);
        es.x = nvp.packet().position().x();
        es.y = nvp.packet().position().y();
        es.z = nvp.packet().position().z();
    }

    private void handleArtilleryShots(DecodedPayload.ArtilleryShotsPayload asp, float elapsed) {
        for (var salvo : asp.salvos()) {
            firedSalvos.add(new ArtillerySalvo(elapsed, salvo, asp.avatarId().value()));
        }
        var ownerEid = asp.avatarId().value();
        var es = entities.get(ownerEid);
        if (es != null) es.shotsFired += asp.salvos().stream().mapToLong(s -> s.shots().size()).sum();
    }

    private void handleTorpedoesReceived(DecodedPayload.TorpedoesReceivedPayload trp, float elapsed) {
        for (var td : trp.torpedoes()) {
            var rec = new TorpedoRecord(elapsed, td);
            torpedoes.add(rec);
            activeTorpedoes.put(torpedoKey(td.ownerId().value(), td.shotId()), rec);
        }
    }

    private void handleShotKills(DecodedPayload.ShotKillsPayload skp, float elapsed) {
        for (var hit : skp.hits()) {
            shotHits.add(new ShotHitRecord(elapsed, skp.avatarId(), hit));
            // 命中即移除对应在飞鱼雷（对标 Rust remove_matching_torpedo）
            activeTorpedoes.remove(torpedoKey(hit.ownerId().value(), hit.shotId()));
        }
    }

    private void handleTorpedoDirection(DecodedPayload.TorpedoDirectionPayload tdp) {
        // Update matching torpedo's maneuver flag
        for (var t : torpedoes) {
            if (t.data.shotId() == tdp.shotId() && t.data.ownerId().value() == tdp.ownerId().value()) {
                torpedoes.set(torpedoes.indexOf(t), t.withManeuver(tdp.targetYaw(), tdp.speedCoef()));
                break;
            }
        }
    }

    private void handleGunSync(DecodedPayload.GunSyncPayload gsp) {
        var es = entities.get(gsp.entityId().value());
        if (es != null) es.turrets.put(gsp.gunId(), new float[]{gsp.yaw(), gsp.pitch()});
    }

    private void handleSetAmmo(DecodedPayload.SetAmmoForWeaponPayload saw) {
        var es = entities.get(saw.entityId().value());
        if (es != null) es.ammoByWeapon.put(saw.weaponType(), saw.ammoParamId().value());
    }

    private void handlePlaneAdded(DecodedPayload.PlaneAddedPayload pap, float elapsed) {
        var ps = new PlaneState(pap.planeId(), pap.entityId().value(), pap.teamId(),
            pap.paramsId(), pap.x(), pap.z(), elapsed, elapsed);
        activePlanes.put(pap.planeId(), ps);
        planeEvents.add(new PlaneRecord(elapsed, "added", pap.planeId(), ps));
    }

    private void handlePlaneRemoved(DecodedPayload.PlaneRemovedPayload prp, float elapsed) {
        var ps = activePlanes.remove(prp.planeId());
        planeEvents.add(new PlaneRecord(elapsed, "removed", prp.planeId(), ps));
    }

    private void handlePlanePosition(DecodedPayload.PlanePositionPayload ppp, float elapsed) {
        var ps = activePlanes.get(ppp.planeId());
        if (ps != null) {
            activePlanes.put(ppp.planeId(), ps.withPosition(ppp.x(), ppp.z(), elapsed));
        }
    }

    private void handleWardAdded(DecodedPayload.WardAddedPayload wap, float elapsed) {
        activeWards.put(wap.planeId(), new WardState(wap.planeId(), wap.entityId(), wap.ownerId(),
            wap.position(), wap.radius(), elapsed));
    }

    private void handleWardRemoved(DecodedPayload.WardRemovedPayload wrp) {
        activeWards.remove(wrp.planeId());
    }

    private void handleDamageStat(DecodedPayload.DamageStatPayload dsp) {
        // receiveDamageStat 是服务端权威的自我玩家伤害统计（服务端覆盖 AoI 外持续伤害）。
        // 累积到 world.selfDamageStats（对标 Rust SelfStats.damage_stats）。
        for (var e : dsp.entries()) {
            selfDamageStats.add(new com.wows.replay.ingest.report.DamageStatEntry(
                e.weaponId(),
                com.wows.replay.ingest.report.DamageStatCategory.fromRaw(e.categoryId()),
                e.count(), e.total()));
        }
    }

    private void handleMinimapUpdate(DecodedPayload.MinimapUpdatePayload mup, float elapsed) {
        for (var entry : mup.updates()) {
            var es = getOrCreateEntity(entry.entityId().value(), null);
            es.isInvisible = !entry.visible();
            es.visible = entry.visible();
            es.lastUpdated = elapsed;
            // 不可见/哨兵时保留上次位置与朝向（对标 Rust MinimapPlacement 保留逻辑）
            if (entry.visible() && !entry.isSentinel()) {
                es.minimapX = entry.x();
                es.minimapZ = entry.z();
                es.minimapHeading = entry.heading();
            }
        }
    }

    private void handleRibbon(DecodedPayload.RibbonPayload rp, float elapsed) {
        ribbonLog.add(new RibbonEvent(elapsed, rp.ribbonId()));
    }

    private void handleVoiceLine(DecodedPayload.VoiceLinePayload vl, float elapsed) {
        voiceLineLog.add(new VoiceLineEvent(elapsed, vl.senderId(), vl.isGlobal(), vl.message()));
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
        arenaPlayers.put(dbId, psd);

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
        es.kind = type; // created kind; a player's Vehicle reuses the Avatar id, so kind is the authoritative marker
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
                // Captain: crewModifiersCompactParams.paramsId（EntityCreate 时冻结，永不刷新）
                ArgValue cmcp = props.get("crewModifiersCompactParams");
                if (cmcp instanceof ArgValue.DictVal d) {
                    ArgValue pid = d.entries().get("paramsId");
                    if (pid instanceof ArgValue.IntVal iv) {
                        es.captainParamsId = iv.value();
                    }
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
                smokeScreens.put(eid, es);
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
                buffZones.put(eid, new BuffZoneState(eid, bfx, bfz, bfr, bfTeam, bfActive, null));
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
            teamWinScore = winScore;

            // hold: [{ reward, period, cpIndices }] → scoring_rules
            ArgValue hold = md.entries().get("hold");
            if (hold instanceof ArgValue.ArrayVal ha && !ha.elements().isEmpty()) {
                ArgValue first = ha.elements().get(0);
                if (first instanceof ArgValue.DictVal hd) {
                    if (hd.entries().get("reward") instanceof ArgValue.IntVal riv) holdReward = riv.value();
                    if (hd.entries().get("period") instanceof ArgValue.FloatVal pfv) holdPeriod = (float) pfv.value();
                    else if (hd.entries().get("period") instanceof ArgValue.IntVal piv) holdPeriod = piv.value();
                    ArgValue cpIdx = hd.entries().get("cpIndices");
                    if (cpIdx instanceof ArgValue.ArrayVal ca) {
                        holdCpIndices.clear();
                        for (var e : ca.elements()) {
                            if (e instanceof ArgValue.IntVal civ) holdCpIndices.add((int) civ.value());
                        }
                    }
                }
            }
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
                cpState.entityId = eid;
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
                buffZones.put(eid, new BuffZoneState(eid, px, pz, radius, teamId, active, null));
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
            // 15.x: onBattleEnd carries no args; win/finish arrive via BattleLogic
            // `battleResult` property: { winnerTeamId, finishReason }.
            case BATTLE_RESULT -> {
                if (val instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                    ArgValue winner = d.get("winnerTeamId");
                    if (winner instanceof ArgValue.IntVal iv) {
                        long w = iv.value();
                        if (w >= -1) {
                            winningTeam = (int) w;
                            battleResultClock = elapsed;
                        }
                    }
                    ArgValue reason = d.get("finishReason");
                    if (reason instanceof ArgValue.IntVal iv2 && iv2.value() > 0) {
                        finishType = finishTypeName((int) iv2.value());
                        finishTypeId = (int) iv2.value();
                    }
                }
            }
            case BATTLE_STAGE -> {
                // BATTLE_STAGES: 0=Waiting, 1=Battle, 2=Results, 3=Finishing, 4=Ended.
                if (val instanceof ArgValue.IntVal iv) {
                    battleStageId = (int) iv.value();
                    if (iv.value() == 0 && battleStartClock == null) {
                        battleStartClock = elapsed;
                    }
                }
            }
            case TIME_LEFT -> {
                if (val instanceof ArgValue.IntVal iv) timeLeft = (float) iv.value();
                else if (val instanceof ArgValue.FloatVal fv) timeLeft = (float) fv.value();
            }
            case VISIBILITY_FLAGS -> {
                var es = getOrCreateEntity(eid, null);
                es.visibilityFlags = intFromArg(val);
            }
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

        // Only try PickleReader if the data looks like pickle（单一来源见
        // PickleReader.isSupportedFirstByte，覆盖 parse() 支持的全部 opcode）
        if (raw.length == 0) return;
        if (!PickleReader.isSupportedFirstByte(raw[0])) return;

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

        // fail-visible：版本结构变化时打出未识别路径，而不是静默丢数据
        boolean handled = false;

        // state → missions → teamsScore → [N] → SetKey{score}
        if (levels.size() >= 3
            && "missions".equals(strVal(levels.get(0)))
            && "teamsScore".equals(strVal(levels.get(1)))
            && levels.get(2) instanceof Long teamIdx
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            handled = true;
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
            handled = true;
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
                case "progress"     -> cp.progress = progressOf(val);
            }
        }

        // state → weather → localWeather → [N] → SetKey
        if (levels.size() >= 3
            && "weather".equals(strVal(levels.get(0)))
            && "localWeather".equals(strVal(levels.get(1)))
            && levels.get(2) instanceof Long wzIdx
            && "SetKey".equals(strVal(action.get("_action"))))
        {
            handled = true;
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
            handled = true;
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

        // fail-visible：未识别的 state 更新路径——版本结构变化时可见，而非静默丢数据
        if (!handled) {
            log.debug("state 更新未识别: levels={} action={}", levels, action);
        }
    }

    private void ingestSmokePointsUpdate(int entityId, Object decoded) {
        // SmokeScreen 'points' 形状精化更新暂未实现（EntityCreate 已建基础烟幕）；
        // fail-visible：记录到达，避免静默丢失。
        log.debug("SmokeScreen points update: entity={} decoded={}", entityId, decoded);
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
            // 只更新该 InteractiveZone 实体对应的占领点，避免多占领点地图互相串数据
            CapturePointState target = null;
            for (var cp : capturePoints) {
                if (cp.entityId == entityId) { target = cp; break; }
            }
            if (target == null) {
                log.debug("componentsState 更新找不到对应占领点: entity={} key={}", entityId, key);
                return;
            }
            switch (key) {
                case "hasInvaders"  -> target.hasInvaders = longVal(val) != 0;
                case "invaderTeam"  -> target.invaderTeam = longVal(val);
                case "progress"     -> target.progress = progressOf(val);
                case "bothInside"   -> target.bothInside = longVal(val) != 0;
                case "isEnabled"    -> target.isEnabled = longVal(val) != 0;
            }
        }
    }

    /** 占领点 progress 是 (value, pointsPerSecond) 二元组（java-port.md §8.6），取第一项。 */
    private static float progressOf(Object val) {
        if (val instanceof Number n) return n.floatValue();
        if (val instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number n) return n.floatValue();
        return 0f;
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
        // Played/extra duration, mirroring Rust report.rs: battle start (BattleStage
        // → Waiting) through match end (battleResult clock, else BattleEnd clock).
        if (battleStartClock != null) {
            Float matchEnd = battleResultClock != null ? battleResultClock : battleEndClock;
            if (matchEnd != null) playedDuration = matchEnd - battleStartClock;
        }
        if (battleResultClock != null && battleEndClock != null && battleEndClock > battleResultClock) {
            extraDuration = battleEndClock - battleResultClock;
        }

        // Match result (Win/Loss/Draw) from winning team vs the recording player's team.
        if (matchResult == null && winningTeam != null && battleEndClock != null) {
            int selfTeam = -1;
            for (var pi : players.values()) {
                if (pi.relation == 0) { selfTeam = pi.teamId; break; }
            }
            if (selfTeam >= 0) {
                if (winningTeam == -1) matchResult = "Draw";
                else if (winningTeam == selfTeam) matchResult = "Win";
                else matchResult = "Loss";
            }
        }

        var entityTypeCounts = new LinkedHashMap<String, Integer>();
        for (var es : entities.values()) entityTypeCounts.merge(es.type, 1, Integer::sum);
        log.info("BattleWorld finish: {} entities (by type: {}, kinds={}), {} players, {} kills, {} damage, {} chat, {} consumables",
            entities.size(), entityTypeCounts, entityKinds(), players.size(), killLog.size(),
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

    /**
     * 消费当前状态，产出一个可序列化的终局快照（对标 Rust
     * {@code BattleWorld::into_report()}）。应在 {@link #finish()} 之后调用。
     */
    public BattleSnapshot intoReport() {
        var playerSnapshots = new ArrayList<BattleSnapshot.Player>();
        for (var e : players.entrySet()) {
            var pi = e.getValue();
            var es = entities.get(pi.entityId);
            boolean dead = es != null && !es.isAlive;
            // 伤害按 aggressor 实体 id（Vehicle）记账，玩家查询需反查其车辆实体，
            // 否则独立车辆实体与 Avatar 分离时伤害会漏算（对齐 BattleReportBuilder）。
            int vehicleEid = resolveVehicleEid(pi.entityId);
            double damage = damageByAggressor.getOrDefault(vehicleEid, List.of())
                .stream().mapToDouble(d -> d.amount).sum();
            playerSnapshots.add(new BattleSnapshot.Player(
                e.getKey(), pi.username, pi.entityId, pi.teamId, pi.relation,
                es != null && es.isBot, dead, damage));
        }
        playerSnapshots.sort(Comparator.comparingLong(BattleSnapshot.Player::dbId));

        var killSnapshots = killLog.stream()
            .map(k -> new BattleSnapshot.Kill(k.clock(), k.killerEid(), k.killerName(),
                k.victimEid(), k.victimName(), k.cause()))
            .toList();
        var chatSnapshots = chatLog.stream()
            .map(c -> new BattleSnapshot.Chat(c.clock(), c.dbId(), c.senderName(), c.channel(), c.message()))
            .toList();
        var cpSnapshots = capturePoints.stream()
            .map(cp -> new BattleSnapshot.CapturePoint(cp.index, cp.teamId, cp.invaderTeam,
                cp.progress, cp.isEnabled,
                cp.position != null && cp.position.length >= 2 ? cp.position[0] : 0,
                cp.position != null && cp.position.length >= 2 ? cp.position[1] : 0))
            .toList();
        var deadShipSnapshots = deadShips.stream()
            .map(ds -> new BattleSnapshot.DeadShip(ds.clock(), ds.victimId(), ds.x(), ds.z()))
            .toList();

        Long arenaIdLong = null;
        if (arenaId != null) {
            try {
                arenaIdLong = Long.parseLong(arenaId);
            } catch (NumberFormatException ignored) { }
        }

        return new BattleSnapshot(
            version.toString(),
            mapName,
            arenaIdLong,
            gameMode,
            meta.gameType(),
            matchGroup,
            winningTeam,
            finishType,
            matchResult,
            maxDuration != null ? maxDuration : 0f,
            playedDuration,
            extraDuration,
            battleStartClock,
            playerSnapshots,
            killSnapshots,
            chatSnapshots,
            damageEvents.size(),
            consumableLog.size(),
            ribbonLog.size(),
            voiceLineLog.size(),
            firedSalvos.size(),
            torpedoes.size(),
            shotHits.size(),
            planeEvents.size(),
            activeWards.size(),
            cpSnapshots,
            buffZones.size(),
            weatherZones.size(),
            buildings.size(),
            deadShipSnapshots
        );
    }

    /** 反查 vehicleToOwner 得到玩家车辆实体 id；玩家船复用 Avatar id 时就是它自己。 */
    public int resolveVehicleEid(int playerEntityId) {
        for (var e : vehicleToOwner.entrySet()) {
            if (e.getValue() == playerEntityId) return e.getKey();
        }
        return playerEntityId;
    }

    // ── Dumper 公开访问器（对标 Rust BattleWorld read API）─────────────

    public GameClock currentClock() { return currentClock; }
    public GameConstantsProvider constants() { return constants; }
    public String arenaId() { return arenaId; }
    public String mapName() { return mapName; }
    public long mapArenaId() { return mapArenaId; }
    public int gameMode() { return gameMode; }
    public String matchGroup() { return matchGroup; }
    public Integer winningTeam() { return winningTeam; }
    public String finishType() { return finishType; }
    public String matchResult() { return matchResult; }
    public Float maxDuration() { return maxDuration; }
    public Float playedDuration() { return playedDuration; }
    public Float extraDuration() { return extraDuration; }
    public Float battleStartClock() { return battleStartClock; }
    public Float battleResultClock() { return battleResultClock; }
    public Float battleEndClock() { return battleEndClock; }
    public boolean matchFinished() { return matchFinished; }
    public int finishTypeId() { return finishTypeId; }
    public String battleResultsJson() { return battleResultsJson; }
    public List<com.wows.replay.ingest.report.DamageStatEntry> selfDamageStats() { return selfDamageStats; }
    public Float timeLeft() { return timeLeft; }

    public Map<Integer, EntityState> entities() { return entities; }
    public Map<Long, PlayerInfo> players() { return players; }
    public Map<Integer, PlayerLink> entityToPlayer() { return entityToPlayer; }
    public Map<Integer, Integer> vehicleToOwner() { return vehicleToOwner; }
    public Map<Long, com.wows.replay.decode.PlayerStateData> arenaPlayers() { return arenaPlayers; }

    public List<TeamScore> teamScores() { return teamScores; }
    public List<KillRecord> killLog() { return killLog; }
    public List<DamageEvent> damageEvents() { return damageEvents; }
    public Map<Integer, List<DamageEvent>> damageByAggressor() { return damageByAggressor; }
    public List<ChatEvent> chatLog() { return chatLog; }
    public List<ConsumableEvent> consumableLog() { return consumableLog; }
    public List<CapturePointState> capturePoints() { return capturePoints; }
    public Map<Integer, BuffZoneState> buffZones() { return buffZones; }
    public List<WeatherZoneState> weatherZones() { return weatherZones; }
    public List<BuildingState> buildings() { return buildings; }
    public List<DeadShipRecord> deadShips() { return deadShips; }
    public List<CapturedBuff> capturedBuffs() { return capturedBuffs; }

    public List<ArtillerySalvo> firedSalvos() { return firedSalvos; }
    public List<TorpedoRecord> torpedoes() { return torpedoes; }
    public Map<Long, TorpedoRecord> activeTorpedoes() { return activeTorpedoes; }

    private static long torpedoKey(int ownerId, int shotId) {
        return ((long) ownerId << 32) | (shotId & 0xFFFFFFFFL);
    }
    public List<ShotHitRecord> shotHits() { return shotHits; }
    public List<PlaneRecord> planeEvents() { return planeEvents; }
    public Map<Long, PlaneState> activePlanes() { return activePlanes; }
    public Map<Long, WardState> activeWards() { return activeWards; }
    public List<VoiceLineEvent> voiceLineLog() { return voiceLineLog; }
    public List<RibbonEvent> ribbonLog() { return ribbonLog; }

    public Set<String> entityTypes() { return entityTypes; }
    public Map<Integer, EntityState> smokeScreens() { return smokeScreens; }
    public Integer battleStageId() { return battleStageId; }
    public long teamWinScore() { return teamWinScore; }
    public long holdReward() { return holdReward; }
    public float holdPeriod() { return holdPeriod; }
    public List<Integer> holdCpIndices() { return holdCpIndices; }

    /**
     * 存活实体按 kind 统计，镜像 Rust {@code entity_kinds()}：只数携带
     * Vehicle/Building/SmokeScreen 类型组件且仍存活的实体。玩家船复用 Avatar id，
     * 故以 EntityCreate 时记录的 {@code kind} 为准。
     */
    public int entityKinds() {
        return (int) entities.values().stream()
            .filter(es -> es.kind != null)
            .filter(es -> switch (es.kind) {
                case "Vehicle", "Building", "SmokeScreen" -> true;
                default -> false;
            })
            .count();
    }

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

    /** Resolve a FINISH_TYPE id from battle.xml to a display name. */
    static String finishTypeName(int id) {
        return switch (id) {
            case 0 -> "Unknown";
            case 1 -> "Extermination";
            case 2 -> "BaseCaptured";
            case 3 -> "Timeout";
            case 4 -> "Failure";
            case 5 -> "Technical";
            case 8 -> "Score";
            case 9 -> "ScoreOnTimeout";
            case 10 -> "PveMainTaskSucceeded";
            case 11 -> "PveMainTaskFailed";
            case 12 -> "ScoreZero";
            case 13 -> "ScoreExcess";
            default -> "FinishType(" + id + ")";
        };
    }

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
        /** 对应 InteractiveZone 实体 id（componentsState 更新定位用），-1 表示未知。 */
        public int entityId = -1;
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
    public record ArtillerySalvo(float clock, com.wows.replay.decode.DecodedPayload.ArtillerySalvo salvo, int avatarId) {}

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
