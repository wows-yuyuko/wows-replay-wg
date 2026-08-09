package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayMeta;
import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.core.decode.PlayerStateData;
import com.shinoaki.wowsreplay.core.decode.PropertyDecoder;
import com.shinoaki.wowsreplay.core.model.*;
import com.shinoaki.wowsreplay.core.packet.*;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.core.types.ArgValue;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;
import com.shinoaki.wowsreplay.ingest.report.DamageStatCategory;
import com.shinoaki.wowsreplay.ingest.report.DamageStatEntry;
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
    /** 统一常量布局管理器（id→名称，优先本类规范表，外部 provider 兜底，见 GameConstants）。 */
    private final GameConstants gameConstants;
    /** 当前推进时钟（§12.4.4），由 process() 按规则更新。 */
    private GameClock currentClock = GameClock.ZERO;

    // ── Entity state ───────────────────────────────────────────────────
    /** entity_id → EntityState */
    final Map<Integer, EntityState> entities = new LinkedHashMap<>();

    // ── Resources ──────────────────────────────────────────────────────
    final List<TeamScore> teamScores = new ArrayList<>();
    final List<KillRecord> killLog = new ArrayList<>();
    final List<DamageEvent> damageEvents = new ArrayList<>();
    final Map<Integer, List<DamageEvent>> damageByAggressor = new LinkedHashMap<>();
    final List<ChatEvent> chatLog = new ArrayList<>();
    final List<ConsumableEvent> consumableLog = new ArrayList<>();
    final List<CapturePointState> capturePoints = new ArrayList<>();
    /** Active buff zones keyed by entity id (despawned on EntityLeave, mirrors Rust). */
    final Map<Integer, BuffZoneState> buffZones = new LinkedHashMap<>();
    final List<WeatherZoneState> weatherZones = new ArrayList<>();
    final List<BuildingState> buildings = new ArrayList<>();
    final List<DeadShipRecord> deadShips = new ArrayList<>();
    final List<CapturedBuff> capturedBuffs = new ArrayList<>();
    final Set<String> entityTypes = new LinkedHashSet<>();

    // ── Extended resources (Phase 4 ingest) ────────────────────────────
    final List<ArtillerySalvo> firedSalvos = new ArrayList<>();
    final List<TorpedoRecord> torpedoes = new ArrayList<>();
    /** 在飞鱼雷（命中时移除），对标 Rust ActiveTorpedoOrder */
    final Map<Long, TorpedoRecord> activeTorpedoes = new LinkedHashMap<>();
    final List<ShotHitRecord> shotHits = new ArrayList<>();
    final List<PlaneRecord> planeEvents = new ArrayList<>();
    final Map<Long, PlaneState> activePlanes = new LinkedHashMap<>();
    final Map<Long, WardState> activeWards = new LinkedHashMap<>();
    final List<VoiceLineEvent> voiceLineLog = new ArrayList<>();
    final List<RibbonEvent> ribbonLog = new ArrayList<>();

    String arenaId;
    String mapName;
    long mapArenaId;
    int gameMode;
    String matchGroup;
    /** 获胜队伍（0/1=队伍索引，-1=平局；0 亦为未知哨兵，配合 battleEndClock!=0 判"已结束"）。 */
    int winningTeam;
    String finishType;
    String matchResult;
    float maxDuration;
    float playedDuration;
    float extraDuration;
    float battleStartClock;
    float battleResultClock;
    float battleEndClock;
    /** 收到 BattleEnd 置 true（匹配 report.rs MatchState.match_finished）。 */
    boolean matchFinished;
    /** finishType 原始 int（battle.xml FINISH_TYPE id）。 */
    int finishTypeId;
    /** 0x22 BattleResults 原始 JSON 字符串。 */
    String battleResultsJson;
    /** receiveDamageStat 累积（服务端权威的自我玩家按武器伤害）。 */
    final List<DamageStatEntry> selfDamageStats = new ArrayList<>();
    /** BattleLogic timeLeft 属性（秒），minimap frame 用 */
    float timeLeft;
    /** BattleLogic battleStage 属性 id（BATTLE_STAGES：0=Waiting,1=Battle,2=Results,3=Finishing,4=Ended） */
    int battleStageId;
    /** 存活烟幕（EntityLeave 时移除），minimap frame 用 */
    final Map<Integer, EntityState> smokeScreens = new LinkedHashMap<>();
    // ── 计分规则（BattleLogic state.missions.hold，minimap scoring_rules 用）────
    long teamWinScore;
    long holdReward;
    float holdPeriod;
    final List<Integer> holdCpIndices = new ArrayList<>();

    // ── Player mapping ─────────────────────────────────────────────────
    /** entity_id → (meta_id, username) */
    final Map<Integer, PlayerLink> entityToPlayer = new LinkedHashMap<>();
    /** meta_id → PlayerInfo（战斗内 meta id，与 meta.vehicles[].id 同空间） */
    final Map<Long, PlayerInfo> players = new LinkedHashMap<>();
    /** meta_id → 竞技场名册原始状态（dumper 输出 initial_state 用） */
    final Map<Long, PlayerStateData> arenaPlayers = new LinkedHashMap<>();
    /** Vehicle entity_id → Avatar entity_id (owner) */
    final Map<Integer, Integer> vehicleToOwner = new LinkedHashMap<>();
    /** meta_id → entity_id (from arena state) */
    final Map<Long, Integer> dbToEntity = new LinkedHashMap<>();

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
        this.gameConstants = new GameConstants(this.constants);
        this.metaPlayers = new ArrayList<>();
        this.gameMode = meta.gameMode();
        this.matchGroup = meta.matchGroup();
        this.maxDuration = (float) meta.duration();

        // Pre-seed players from replay metadata
        var vehicles = meta.vehicles();
        if (vehicles != null) {
            for (var v : vehicles) {
                // v.id() 是战斗内 meta id（= PlayerStateData.metaShipId()），不是账号 ID
                long metaId = Integer.toUnsignedLong(v.id().value());
                String name = v.name();
                metaPlayers.add(new MetaPlayer(metaId, name, v.relation(), v.shipId().value()));
                players.put(metaId, new PlayerInfo(name, 0, v.relation()));
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
                kl != null ? kl.metaId() : 0, kl != null ? kl.username() : "",
                vl != null ? vl.metaId() : 0, vl != null ? vl.username() : "",
                sd.cause()));        var es = entities.get(sd.victim().value());
        if (es != null) es.isAlive = false;
        deadShips.add(new DeadShipRecord(elapsed, sd.victim().value(),
                es != null ? es.x : 0, es != null ? es.z : 0));
    }

    private void handleChat(DecodedPayload.ChatMessagePayload chat, float elapsed) {
        // 发送者是 args[0] 的账号 ID（与 meta/arena 的 id 字段同空间 = 战斗内 meta id），
        // 不能用接收方 entity_id（即 replay 主视角 Avatar）来归属消息。
        long senderMetaId = Integer.toUnsignedLong(chat.senderId().value());
        // System messages carry sender_id 0 and are dropped (mirrors Rust).
        if (senderMetaId == 0) return;
        var pl = players.get(senderMetaId);
        chatLog.add(new ChatEvent(elapsed, chat.entityId().value(),
            senderMetaId,
            pl != null ? pl.username : "account " + senderMetaId,
            chat.audience(), chat.message()));
    }

    private void handleConsumable(DecodedPayload.ConsumablePayload cons, float elapsed) {
        var pl = entityToPlayer.get(cons.entity().value());
        consumableLog.add(new ConsumableEvent(elapsed, cons.entity().value(),
                pl != null ? pl.metaId() : 0, pl != null ? pl.username() : "",
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
            var node = JsonMapper.readTree(br.json());
            if (node.has("matchResult")) matchResult = node.get("matchResult").asString();
            if (node.has("finishReason") && finishType == null)
                finishType = node.get("finishReason").asString();
        } catch (Exception ignored) {
        }
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
        // 命中包到达瞬间快照被命中船（victim）的位置：比时钟边界处理时再查 world.entities()
        // 更接近命中时刻（边界内位置已被后续 Position 包更新）。打海水/空射不产生 receiveShotKills。
        var victimEs = entities.get(skp.avatarId().value());
        Vec3 victimPosition = victimEs != null
                ? new Vec3(victimEs.x, victimEs.y, victimEs.z) : null;
        for (var hit : skp.hits()) {
            shotHits.add(new ShotHitRecord(elapsed, skp.avatarId(), hit, victimPosition));
            // 命中即移除对应在飞鱼雷（对标 Rust remove_matching_torpedo）
            activeTorpedoes.remove(torpedoKey(hit.ownerId().value(), hit.shotId()));
        }
    }

    private void handleTorpedoDirection(DecodedPayload.TorpedoDirectionPayload tdp) {
        // Update matching torpedo's maneuver flag
        for (var t : torpedoes) {
            if (t.data().shotId() == tdp.shotId() && t.data().ownerId().value() == tdp.ownerId().value()) {
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
            selfDamageStats.add(new DamageStatEntry(
                    e.weaponId(),
                    DamageStatCategory.fromRaw(e.categoryId(), gameConstants, version),
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
        long metaId = psd.metaShipId();
        if (entityId <= 0 || metaId <= 0) return;

        // Map entity → player (players 表按战斗内 meta id 索引，与 meta.vehicles[].id 同空间)
        entityToPlayer.put(entityId, new PlayerLink(metaId, psd.username()));

        // Update or create player info
        var existing = players.get(metaId);
        if (existing != null) {
            existing.entityId = entityId;
            existing.teamId = (int) psd.teamId();
            existing.accountId = psd.dbId();
        } else {
            var pi = new PlayerInfo(psd.username(), entityId, 0); // relation unknown for bots
            pi.teamId = (int) psd.teamId();
            pi.accountId = psd.dbId();
            players.put(metaId, pi);
        }
        dbToEntity.put(metaId, entityId);
        arenaPlayers.put(metaId, psd);

        // Create entity components from arena state
        var es = getOrCreateEntity(entityId, "Avatar");
        es.maxHealth = psd.maxHealth();
        es.health = psd.maxHealth(); // seed full HP from arena state
        es.teamId = (int) psd.teamId();
        es.isBot = isBot;
        es.metaId = metaId;
        es.playerName = psd.username();

        // Match meta player by metaShipId → get relation
        for (var mp : metaPlayers) {
            if (mp.metaId == metaId) {
                es.relation = mp.relation;
                mp.accountId = psd.dbId();
                mp.entityId = entityId;
                break;
            }
        }
    }

    private void ingestNewPlayers(List<PlayerStateData> players, List<PlayerStateData> bots) {
        for (var psd : players) ingestOneArenaPlayer(psd, false);
        for (var psd : bots) ingestOneArenaPlayer(psd, true);
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
                if (props.get("owner") instanceof ArgValue.IntVal(long value)) {
                    int ownerEid = (int) value;
                    vehicleToOwner.put(eid, ownerEid);
                    getOrCreateEntity(ownerEid, "Avatar");
                }
                extractHealth(props, eid);
                extractTeam(props, eid);
                // Extract shipConfig if present
                if (props.get("shipConfig") instanceof ArgValue.BlobVal(byte[] value)) {
                    es.shipConfig = value;
                }
                // Captain: crewModifiersCompactParams.paramsId（EntityCreate 时冻结，永不刷新）
                if (props.get("crewModifiersCompactParams") instanceof ArgValue.DictVal(Map<String, ArgValue> cmcp)
                    && cmcp.get("paramsId") instanceof ArgValue.IntVal(long value)) {
                    es.captainParamsId = value;
                }
            }
            case "Avatar" -> {
                extractHealth(props, eid);
                extractTeam(props, eid);
                // Link to player by meta id if present。注意：15.x 的 accountDBID prop 是账号空间，
                // 与 players 表（按 meta id 索引）不同，只有值恰好是已知 meta id 才关联（兜底，通常不命中）。
                if (props.get("accountDBID") instanceof ArgValue.IntVal(long value)
                    && players.containsKey(value)) {
                    es.metaId = value;
                    es.playerName = players.get(value).username;
                    entityToPlayer.putIfAbsent(eid, new PlayerLink(value, es.playerName));
                    var pi = players.get(value);
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
                float bx = posX(ec.position());
                float bz = posZ(ec.position());
                int teamId = getIntProp(props, "teamId");
                long paramsId = getLongProp(props, "paramsId");
                boolean alive = getBoolProp(props, "isAlive", true);
                buildings.add(new BuildingState(eid, bx, bz, teamId, paramsId, alive));
            }
            case "SmokeScreen" -> {
                es.smokeRadius = getFloatProp(props, "radius");
                smokeScreens.put(eid, es);
            }
            case "WeatherZone", "LocalWeatherZone" -> {
                float wx = posX(ec.position());
                float wz = posZ(ec.position());
                float wr = getFloatProp(props, "radius");
                long wparams = getLongProp(props, "paramsId");
                // Decode name from byte array
                String wname = decodeName(props.get("name"));
                weatherZones.add(new WeatherZoneState(wname, wx, wz, wr, wparams, eid));
            }
            case "BuffZone" -> {
                float bfx = posX(ec.position());
                float bfz = posZ(ec.position());
                float bfr = getFloatProp(props, "radius");
                int bfTeam = getIntProp(props, "teamId");
                boolean bfActive = getBoolProp(props, "isActive", true);
                buffZones.put(eid, new BuffZoneState(eid, bfx, bfz, bfr, bfTeam, bfActive, null));
            }
        }
    }

    private void ingestBattleLogic(Map<String, ArgValue> props) {
        ArgValue state = props.get("state");
        if (!(state instanceof ArgValue.DictVal(Map<String, ArgValue> entries))) return;

        // Team scores
        ArgValue missions = entries.get("missions");
        if (missions instanceof ArgValue.DictVal(Map<String, ArgValue> entries1)) {
            ArgValue ts = entries1.get("teamsScore");
            if (ts instanceof ArgValue.ArrayVal(List<ArgValue> elements)) {
                for (int i = 0; i < elements.size(); i++) {
                    ArgValue entry = elements.get(i);
                    if (entry instanceof ArgValue.DictVal(Map<String, ArgValue> entries2)) {
                        ArgValue score = entries2.get("score");
                        if (score instanceof ArgValue.IntVal(long value)) {
                            ensureTeamScore(i);
                            teamScores.set(i, new TeamScore(i, value));
                        }
                    }
                }
            }

            // Scoring rules
            teamWinScore =  entries1.get("teamWinScore") instanceof ArgValue.IntVal(long value) ? value : 1000;

            // hold: [{ reward, period, cpIndices }] → scoring_rules
            if (entries1.get("hold") instanceof ArgValue.ArrayVal(List<ArgValue> holdElements)
                && !holdElements.isEmpty()) {
                if (holdElements.getFirst() instanceof ArgValue.DictVal(Map<String, ArgValue> hd)) {
                    if (hd.get("reward") instanceof ArgValue.IntVal(long value)) holdReward = value;
                    if (hd.get("period") instanceof ArgValue.FloatVal(double value)) holdPeriod = (float) value;
                    else if (hd.get("period") instanceof ArgValue.IntVal(long value)) holdPeriod = value;
                    if (hd.get("cpIndices") instanceof ArgValue.ArrayVal(List<ArgValue> cpIndices)) {
                        holdCpIndices.clear();
                        for (ArgValue e : cpIndices) {
                            if (e instanceof ArgValue.IntVal(long value)) holdCpIndices.add((int) value);
                        }
                    }
                }
            }
        }

        // Weather zones seeded from BattleLogic state
        if (entries.get("weather") instanceof ArgValue.DictVal(Map<String, ArgValue> wd)) {
            if (wd.get("localWeather") instanceof ArgValue.ArrayVal(List<ArgValue> lwElements)) {
                for (var lwVal : lwElements) {
                    if (lwVal instanceof ArgValue.DictVal(Map<String, ArgValue> lw)) {
                        String name = decodeName(lw.get("name"));
                        float wx = 0, wz = 0, wr = 0;
                        if (lw.get("position") instanceof ArgValue.Vec2Val(float x, float y)) {
                            wx = x;
                            wz = y;
                        } else if (lw.get("position") instanceof ArgValue.ArrayVal(List<ArgValue> pa) && pa.size() >= 2) {
                            wx = floatFromArg(pa.get(0));
                            wz = floatFromArg(pa.get(1));
                        }
                        if (lw.get("radius") instanceof ArgValue.FloatVal(double value)) wr = (float) value;
                        long paramsId = lw.get("paramsId") instanceof ArgValue.IntVal(long value) ? value : 0;
                        weatherZones.add(new WeatherZoneState(name, wx, wz, wr, paramsId, null));
                    }
                }
            }
        }
    }

    private void ingestInteractiveZone(int eid, Map<String, ArgValue> props, Vec3 position) {
        float px = posX(position);
        float pz = posZ(position);
        float radius = getFloatProp(props, "radius");
        int teamId = getIntProp(props, "teamId");

        if (props.get("componentsState") instanceof ArgValue.DictVal(Map<String, ArgValue> csd)) {
            if (csd.get("controlPoint") instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                int idx = d.get("index") instanceof ArgValue.IntVal(long value) ? (int) value : capturePoints.size();
                var cpState = new CapturePointState();
                cpState.entityId = eid;
                cpState.index = idx;
                cpState.teamId = teamId;
                cpState.position = new float[]{px, pz};
                cpState.radius = radius;

                if (csd.get("captureLogic") instanceof ArgValue.DictVal(Map<String, ArgValue> cld)) {
                    applyCpDict(cpState, cld);
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

            // Try to link to player by meta id（prop 值需恰为已知 meta id 才关联）
            for (String key : props.keySet()) {
                if (key.toLowerCase().contains("dbid") || key.toLowerCase().contains("account")
                    || key.toLowerCase().contains("playerid")) {
                    if (props.get(key) instanceof ArgValue.IntVal(long value)) {
                        // Find player name from meta
                        for (var mp : metaPlayers) {
                            if (mp.metaId == value) {
                                var es = getOrCreateEntity(eid, null);
                                es.metaId = value;
                                es.playerName = mp.name;
                                es.relation = mp.relation;
                                entityToPlayer.put(eid, new PlayerLink(value, mp.name));
                                var pi = players.get(value);
                                if (pi != null) pi.entityId = eid;
                                mp.entityId = eid;
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
                    entityToPlayer.put(eid, new PlayerLink(mp.metaId, mp.name));
                    var pi = players.get(mp.metaId);
                    if (pi != null) pi.entityId = eid;
                    if (es != null) {
                        es.metaId = mp.metaId;
                        es.playerName = mp.name;
                        es.relation = 0;
                    }
                    mp.entityId = eid;
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
            case HEALTH -> {
                var es = getOrCreateEntity(eid, null);
                es.health = floatFromArg(val);
            }
            case MAX_HEALTH -> {
                var es = getOrCreateEntity(eid, null);
                es.maxHealth = floatFromArg(val);
            }
            case TEAM_ID -> {
                int tid = intFromArg(val);
                getOrCreateEntity(eid, null).teamId = tid;
                var pl = entityToPlayer.get(eid);
                if (pl != null) {
                    var pi = players.get(pl.metaId());
                    if (pi != null) pi.teamId = tid;
                }
            }
            case IS_ALIVE -> getOrCreateEntity(eid, null).isAlive = intFromArg(val) != 0;
            case IS_INVISIBLE -> getOrCreateEntity(eid, null).isInvisible = intFromArg(val) != 0;
            case MAX_DURATION -> {
                if (val instanceof ArgValue.FloatVal(double value)) maxDuration = (float) value;
            }
            case PLAYED_DURATION -> {
                if (val instanceof ArgValue.FloatVal(double value)) playedDuration = (float) value;
            }
            case EXTRA_DURATION -> {
                if (val instanceof ArgValue.FloatVal(double value)) extraDuration = (float) value;
            }
            case FINISH_TYPE -> {
                if (val instanceof ArgValue.StrVal(String value)) finishType = value;
            }
            case MATCH_RESULT -> {
                if (val instanceof ArgValue.StrVal(String value)) matchResult = value;
            }
            // 15.x: onBattleEnd carries no args; win/finish arrive via BattleLogic
            // `battleResult` property: { winnerTeamId, finishReason }.
            case BATTLE_RESULT -> {
                if (val instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                    if (d.get("winnerTeamId") instanceof ArgValue.IntVal(long value)) {
                        if (value >= -1) {
                            winningTeam = (int) value;
                            battleResultClock = elapsed;
                        }
                    }
                    if (d.get("finishReason") instanceof ArgValue.IntVal(long value) && value > 0) {
                        finishType = gameConstants.finishTypeName((int) value, version);
                        finishTypeId = (int) value;
                    }
                }
            }
            case BATTLE_STAGE -> {
                // BATTLE_STAGES: 0=Waiting, 1=Battle, 2=Results, 3=Finishing, 4=Ended.
                if (val instanceof ArgValue.IntVal(long value)) {
                    battleStageId = (int) value;
                    if (value == 0 && battleStartClock == 0f) {
                        battleStartClock = elapsed;
                    }
                }
            }
            case TIME_LEFT -> {
                if (val instanceof ArgValue.IntVal(long value)) timeLeft = (float) value;
                else if (val instanceof ArgValue.FloatVal(double value)) timeLeft = (float) value;
            }
            case VISIBILITY_FLAGS -> {
                var es = getOrCreateEntity(eid, null);
                es.visibilityFlags = intFromArg(val);
            }
            case STATE -> traverseStateDict(eid, val, elapsed);
            case SHIP_CONFIG -> {
                // shipConfig 二进制 blob：EntityCreate 已捕获，属性更新时再刷新
                if (val instanceof ArgValue.BlobVal(byte[] value)) {
                    getOrCreateEntity(eid, null).shipConfig = value;
                }
            }
            case VEHICLE_ID -> {
                if (val instanceof ArgValue.IntVal(long value)) {
                    getOrCreateEntity(eid, null).vehicleId = new GameParamId((int) value);
                }
            }
            case OWNER_ID, OTHER -> { /* recorded but not yet handled */ }
        }
    }

    /** Traverse nested state dict for team scores, control points, weather updates. */
    private void traverseStateDict(int eid, ArgValue val, float elapsed) {
        if (!(val instanceof ArgValue.DictVal(Map<String, ArgValue> sd))) return;

        // state.missions.teamsScore
        if (sd.get("missions") instanceof ArgValue.DictVal(Map<String, ArgValue> md)) {
            if (md.get("teamsScore") instanceof ArgValue.ArrayVal(List<ArgValue> tsElements)) {
                for (int i = 0; i < tsElements.size(); i++) {
                    ArgValue entry = tsElements.get(i);
                    if (entry instanceof ArgValue.DictVal(Map<String, ArgValue> ed)) {
                        if (ed.get("score") instanceof ArgValue.IntVal(long value)) {
                            ensureTeamScore(i);
                            teamScores.set(i, new TeamScore(i, value));
                        }
                    }
                }
            }
        }

        // state.controlPoints
        if (sd.get("controlPoints") instanceof ArgValue.ArrayVal(List<ArgValue> cpElements)) {
            for (int i = 0; i < cpElements.size(); i++) {
                ensureCpIndex(i);
                var cp = capturePoints.get(i);
                if (cpElements.get(i) instanceof ArgValue.DictVal(Map<String, ArgValue> cpd)) {
                    applyCpDict(cp, cpd);
                }
            }
        }

        // state.weather.localWeather
        if (sd.get("weather") instanceof ArgValue.DictVal(Map<String, ArgValue> wd)) {
            if (wd.get("localWeather") instanceof ArgValue.ArrayVal(List<ArgValue> lwElements)) {
                for (int i = 0; i < lwElements.size(); i++) {
                    if (lwElements.get(i) instanceof ArgValue.DictVal(Map<String, ArgValue> lw)) {
                        String name = decodeName(lw.get("name"));
                        float wx = 0, wz = 0, wr = 0;
                        if (lw.get("position") instanceof ArgValue.Vec2Val(float x, float y)) {
                            wx = x;
                            wz = y;
                        } else if (lw.get("position") instanceof ArgValue.ArrayVal(List<ArgValue> pa) && pa.size() >= 2) {
                            wx = floatFromArg(pa.get(0));
                            wz = floatFromArg(pa.get(1));
                        }
                        if (lw.get("radius") instanceof ArgValue.FloatVal(double value)) wr = (float) value;
                        long paramsId = lw.get("paramsId") instanceof ArgValue.IntVal(long value) ? value : 0;
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
        // 载荷非 pickle：Parser 已解出「位流路径 + 类型化叶子值」（capture-point-audit.md §3），
        // 这里按解码后的 path/action 直接应用。
        NestedUpdate u = pu.update();
        if (u == null) {
            log.debug("PropertyUpdate: entity={} property={} 无解码结果", pu.entityId(), pu.property());
            return;
        }
        String prop = pu.property();
        int eid = pu.entityId().value();
        switch (prop) {
            case "state" -> ingestStatePropertyUpdate(eid, pu.path(), u, elapsed);
            case "componentsState" -> ingestComponentsStateUpdate(eid, pu.path(), u);
            case "points" -> log.debug("SmokeScreen points update: entity={} path={} update={}", eid, pu.path(), u);
            default -> log.debug("PropertyUpdate: entity={} property={} path={} update={}", eid, prop, pu.path(), u);
        }
    }

    /** 按解码后的 path/action 直接应用 state 的子字段更新（BattleLogic 与 Vehicle.state 共用）。 */
    private void ingestStatePropertyUpdate(int eid, List<String> path, NestedUpdate u, float elapsed) {
        var keys = new ArrayList<String>();
        var indexes = new ArrayList<Integer>();
        for (var seg : path) {
            if (seg.startsWith("[") && seg.endsWith("]")) {
                indexes.add(Integer.parseInt(seg.substring(1, seg.length() - 1)));
            } else {
                keys.add(seg);
            }
        }
        boolean handled = false;

        // state.missions.teamsScore = [..]（全量数组 SetKey）
        if (keys.size() == 1 && keys.get(0).equals("missions")
            && u instanceof NestedUpdate.SetKey(String key, ArgValue value1) && key.equals("teamsScore")
            && value1 instanceof ArgValue.ArrayVal(List<ArgValue> arr)) {
            for (int i = 0; i < arr.size(); i++) {
                ensureTeamScore(i);
                teamScores.set(i, new TeamScore(i, scoreOf(arr.get(i))));
            }
            handled = true;
        }
        // state.missions.teamsScore[N].score = v（元素 SetKey 标量叶子）
        if (keys.size() == 2 && keys.get(0).equals("missions") && keys.get(1).equals("teamsScore")
            && !indexes.isEmpty() && u instanceof NestedUpdate.SetKey(String key, ArgValue value1) && key.equals("score")
            && value1 instanceof ArgValue.IntVal(long value)) {
            int idx = indexes.getLast();
            ensureTeamScore(idx);
            teamScores.set(idx, new TeamScore(idx, value));
            handled = true;
        }
        // state.missions.teamsScore[N] = {teamId, score}（数组 SetElement 整元素）
        if (keys.size() == 1 && keys.get(0).equals("missions") && indexes.size() == 1
            && u instanceof NestedUpdate.SetElement se) {
            int idx = indexes.getFirst();
            ensureTeamScore(idx);
            teamScores.set(idx, new TeamScore(idx, scoreOf(se.value())));
            handled = true;
        }
        // state.weather.localWeather[N].{position/radius/paramsId}（SetKey 标量叶子 / SetElement）
        if (keys.size() == 2 && keys.get(0).equals("weather") && keys.get(1).equals("localWeather")
            && !indexes.isEmpty()) {
            int idx = indexes.getLast();
            while (weatherZones.size() <= idx) {
                weatherZones.add(new WeatherZoneState("", 0, 0, 0, 0, null));
            }
            var wz = weatherZones.get(idx);
            if (u instanceof NestedUpdate.SetKey(String key, ArgValue value)) {
                switch (key) {
                    case "position" -> {
                        Float x = null, z = null;
                        if (value instanceof ArgValue.Vec2Val(float vx, float vz)) {
                            x = vx;
                            z = vz;
                        } else if (value instanceof ArgValue.ArrayVal(List<ArgValue> av) && av.size() >= 2) {
                            x = floatFromArg(av.get(0));
                            z = floatFromArg(av.get(1));
                        }
                        if (x != null) {
                            weatherZones.set(idx, new WeatherZoneState(wz.name(), x, z, wz.radius(), wz.paramsId(), wz.entityId()));
                        }
                    }
                    case "radius" -> weatherZones.set(idx, new WeatherZoneState(
                            wz.name(), wz.x(), wz.z(), floatFromArg(value), wz.paramsId(), wz.entityId()));
                    case "paramsId" -> weatherZones.set(idx, new WeatherZoneState(
                            wz.name(), wz.x(), wz.z(), wz.radius(), longOfArg(value), wz.entityId()));
                    default -> log.debug("weather.localWeather 更新未处理: key={}", key);
                }
                handled = true;
            } else if (u instanceof NestedUpdate.SetElement se
                       && se.value() instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                String name = d.get("name") instanceof ArgValue.BlobVal(byte[] b)
                        ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                        : wz.name();
                float x = wz.x(), z = wz.z();
                if (d.get("position") instanceof ArgValue.Vec2Val(float vx, float vz)) {
                    x = vx;
                    z = vz;
                } else if (d.get("position") instanceof ArgValue.ArrayVal(List<ArgValue> av) && av.size() >= 2) {
                    x = floatFromArg(av.get(0));
                    z = floatFromArg(av.get(1));
                }
                float r = d.get("radius") != null ? floatFromArg(d.get("radius")) : wz.radius();
                long pid = d.get("paramsId") != null ? longOfArg(d.get("paramsId")) : wz.paramsId();
                weatherZones.set(idx, new WeatherZoneState(name, x, z, r, pid, wz.entityId()));
                handled = true;
            }
        }

        // ── Vehicle.state 子字段（识别并应用，不再当未识别丢弃）────────────────
        // state.battery.energy = v（主炮能量，FloatVal）
        if (keys.size() == 1 && keys.getFirst().equals("battery")
            && u instanceof NestedUpdate.SetKey(String key, ArgValue value) && key.equals("energy")
            && value instanceof ArgValue.FloatVal(double energy)) {
            getOrCreateEntity(eid, null).batteryEnergy = (float) energy;
            handled = true;
        }
        // state.atba.atbaTargets = [..]（全量数组 SetKey）
        if (keys.size() == 1 && keys.getFirst().equals("atba")
            && u instanceof NestedUpdate.SetKey(String key, ArgValue value) && key.equals("atbaTargets")
            && value instanceof ArgValue.ArrayVal(List<ArgValue> targets)) {
            var es = getOrCreateEntity(eid, null);
            es.atbaTargets = new ArrayList<>(targets.size());
            for (ArgValue t : targets) es.atbaTargets.add(longOfArg(t));
            handled = true;
        }
        // state.atba.atbaTargets[N] = v（数组元素 SetElement / SetRange）
        if (keys.size() == 2 && keys.getFirst().equals("atba") && keys.get(1).equals("atbaTargets")
            && !indexes.isEmpty()) {
            var es = getOrCreateEntity(eid, null);
            if (es.atbaTargets == null) es.atbaTargets = new ArrayList<>();
            if (u instanceof NestedUpdate.SetElement se) {
                int idx = indexes.getLast();
                while (es.atbaTargets.size() <= idx) es.atbaTargets.add(0L);
                es.atbaTargets.set(idx, longOfArg(se.value()));
                handled = true;
            } else if (u instanceof NestedUpdate.SetRange(int start, int stop, List<ArgValue> values)) {
                while (es.atbaTargets.size() <= stop) es.atbaTargets.add(0L);
                for (int i = 0; i < values.size() && start + i <= stop; i++) {
                    es.atbaTargets.set(start + i, longOfArg(values.get(i)));
                }
                handled = true;
            }
        }
        // state.decals.shotDecals[N] = {id, decal}（命中弹痕，外观数据仅计数）
        if (keys.size() == 2 && keys.getFirst().equals("decals") && keys.get(1).equals("shotDecals")) {
            var es = getOrCreateEntity(eid, null);
            if (u instanceof NestedUpdate.SetElement se) {
                es.shotDecals = Math.max(es.shotDecals, indexes.getLast() + 1);
            } else if (u instanceof NestedUpdate.SetRange sr) {
                es.shotDecals = Math.max(es.shotDecals, sr.stop() + 1);
            }
            handled = true;
        }
        // state.weather.globalWeather = {item}（全局天气切换事件，未建模，识别即止）
        if (keys.size() == 2 && keys.getFirst().equals("weather") && keys.get(1).equals("globalWeather")) {
            handled = true;
        }

        if (!handled) {
            log.debug("state 更新未识别: path={} update={}", path, u);
        }
    }

    private void ingestComponentsStateUpdate(int entityId, List<String> path, NestedUpdate u) {
        // componentsState.captureLogic.{field} = v
        if (path.size() == 1 && path.getFirst().equals("captureLogic")
            && u instanceof NestedUpdate.SetKey(String key, ArgValue value)) {
            CapturePointState target = null;
            for (var cp : capturePoints) {
                if (cp.entityId == entityId) {
                    target = cp;
                    break;
                }
            }
            if (target == null) {
                log.debug("componentsState 更新找不到对应占领点: entity={} key={}", entityId, key);
                return;
            }
            switch (key) {
                case "hasInvaders" -> target.hasInvaders = longOfArg(value) != 0;
                case "invaderTeam" -> target.invaderTeam = longOfArg(value);
                case "progress"    -> target.progress = progressOfArg(value);
                case "bothInside"  -> target.bothInside = longOfArg(value) != 0;
                case "isEnabled"   -> target.isEnabled = longOfArg(value) != 0;
                case "captureSpeed" -> target.captureSpeed = floatFromArg(value);
                default -> log.debug("componentsState captureLogic 更新未处理: key={} value={}", key, value);
            }
        } else {
            log.debug("componentsState 更新未识别: entity={} path={} update={}", entityId, path, u);
        }
    }

    /** TEAM_SCORE 元素/字段取 score 值。 */
    private static long scoreOf(ArgValue v) {
        if (v instanceof ArgValue.IntVal(long value)) return value;
        if (v instanceof ArgValue.FloatVal(double value)) return value > 0 ? (long) value : 0;
        if (v instanceof ArgValue.DictVal(Map<String, ArgValue> d)
            && d.get("score") instanceof ArgValue.IntVal(long value)) return value;
        return 0;
    }

    /** 占领点 progress 是 FLOAT（旧 def 可能为 (value, pointsPerSecond) 二元组），取第一项。 */
    private static float progressOfArg(ArgValue v) {
        return switch (v) {
            case ArgValue.FloatVal(double value) -> (float) value;
            case ArgValue.IntVal(long value) -> (float) value;
            case ArgValue.ArrayVal(List<ArgValue> elements) when !elements.isEmpty() -> floatFromArg(elements.getFirst());
            case ArgValue.TupleVal(List<ArgValue> elements) when !elements.isEmpty() -> floatFromArg(elements.getFirst());
            case ArgValue.DictVal(Map<String, ArgValue> d) when d.get("progress") != null -> progressOfArg(d.get("progress"));
            default -> 0f;
        };
    }

    /** ArgValue → long。 */
    private static long longOfArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> iv.value();
            case ArgValue.FloatVal fv -> (long) fv.value();
            case ArgValue.BoolVal bv -> bv.value() ? 1 : 0;
            default -> 0;
        };
    }

    // ── Finish ─────────────────────────────────────────────────────────

    /** Called after all packets have been processed. */
    public void finish() {
        // Played/extra duration, mirroring Rust report.rs: battle start (BattleStage
        // → Waiting) through match end (battleResult clock, else BattleEnd clock).
        // 0 作为"未设置"哨兵（战斗开始/结束时钟实际都远大于 0）。
        if (battleStartClock != 0f) {
            float matchEnd = battleResultClock != 0f ? battleResultClock : battleEndClock;
            if (matchEnd != 0f) playedDuration = matchEnd - battleStartClock;
        }
        if (battleResultClock != 0f && battleEndClock != 0f && battleEndClock > battleResultClock) {
            extraDuration = battleEndClock - battleResultClock;
        }

        // Match result (Win/Loss/Draw) from winning team vs the recording player's team.
        // battleEndClock != 0 作为"已结束"信号（winningTeam 在战斗结束包时必被设置）。
        if (matchResult == null && battleEndClock != 0f) {
            int selfTeam = -1;
            for (var pi : players.values()) {
                if (pi.relation == 0) {
                    selfTeam = pi.teamId;
                    break;
                }
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
            // 伤害按 aggressor 实体 id（Vehicle）记账，玩家查询需反查其战舰实体，
            // 否则独立战舰实体与 Avatar 分离时伤害会漏算（对齐 BattleReportBuilder）。
            int vehicleEid = resolveVehicleEid(pi.entityId);
            double damage = damageByAggressor.getOrDefault(vehicleEid, List.of())
                    .stream().mapToDouble(DamageEvent::amount).sum();
            playerSnapshots.add(new BattleSnapshot.Player(
                    accountIdOf(e.getKey()), pi.username, pi.entityId, pi.teamId, pi.relation,
                    es != null && es.isBot, dead, damage));
        }
        playerSnapshots.sort(Comparator.comparingLong(BattleSnapshot.Player::dbId));

        var killSnapshots = killLog.stream()
                .map(k -> new BattleSnapshot.Kill(k.clock(), k.killerEid(), k.killerName(),
                        k.victimEid(), k.victimName(), k.cause()))
                .toList();
        var chatSnapshots = chatLog.stream()
                .map(c -> new BattleSnapshot.Chat(c.clock(), accountIdOf(c.metaId()), c.senderName(), c.channel(), c.message()))
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

        long arenaIdLong = 0;
        if (arenaId != null) {
            try {
                arenaIdLong = Long.parseLong(arenaId);
            } catch (NumberFormatException ignored) {
            }
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
                maxDuration,
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

    /** 反查 vehicleToOwner 得到玩家战舰实体 id；玩家船复用 Avatar id 时就是它自己。 */
    public int resolveVehicleEid(int playerEntityId) {
        for (var e : vehicleToOwner.entrySet()) {
            if (e.getValue() == playerEntityId) return e.getKey();
        }
        return playerEntityId;
    }

    // ── Dumper 公开访问器（对标 Rust BattleWorld read API）─────────────

    public GameClock currentClock() {
        return currentClock;
    }

    /** meta 花名册（从 ReplayMeta.vehicles[] 预种子，arena 名册到达后回填 accountId/entityId）。 */
    public List<MetaPlayer> metaPlayers() {
        return metaPlayers;
    }

    public GameConstantsProvider constants() {
        return constants;
    }

    /** 回放版本（常量布局按版本解析）。 */
    public Version version() {
        return version;
    }

    /** 统一常量布局管理器（id→名称），见 {@link GameConstants}。 */
    public GameConstants gameConstants() {
        return gameConstants;
    }

    public String arenaId() {
        return arenaId;
    }

    public String mapName() {
        return mapName;
    }

    public long mapArenaId() {
        return mapArenaId;
    }

    public int gameMode() {
        return gameMode;
    }

    public String matchGroup() {
        return matchGroup;
    }

    public int winningTeam() {
        return winningTeam;
    }

    public String finishType() {
        return finishType;
    }

    public String matchResult() {
        return matchResult;
    }

    public float maxDuration() {
        return maxDuration;
    }

    public float playedDuration() {
        return playedDuration;
    }

    public float extraDuration() {
        return extraDuration;
    }

    public float battleStartClock() {
        return battleStartClock;
    }

    public float battleResultClock() {
        return battleResultClock;
    }

    public float battleEndClock() {
        return battleEndClock;
    }

    public boolean matchFinished() {
        return matchFinished;
    }

    public int finishTypeId() {
        return finishTypeId;
    }

    public String battleResultsJson() {
        return battleResultsJson;
    }

    public List<DamageStatEntry> selfDamageStats() {
        return selfDamageStats;
    }

    public float timeLeft() {
        return timeLeft;
    }

    public Map<Integer, EntityState> entities() {
        return entities;
    }

    public Map<Long, PlayerInfo> players() {
        return players;
    }

    /**
     * 战斗内 meta id → 账号 ID（accountDBID）。
     * 未知时回退为 meta id 本身（保证输出非 0，且与传入值同空间一致）。
     */
    public long accountIdOf(long metaId) {
        var pi = players.get(metaId);
        if (pi != null && pi.accountId != 0) return pi.accountId;
        var arena = arenaPlayers.get(metaId);
        return arena != null && arena.dbId() != 0 ? arena.dbId() : metaId;
    }

    public Map<Integer, PlayerLink> entityToPlayer() {
        return entityToPlayer;
    }

    public Map<Integer, Integer> vehicleToOwner() {
        return vehicleToOwner;
    }

    public Map<Long, PlayerStateData> arenaPlayers() {
        return arenaPlayers;
    }

    public List<TeamScore> teamScores() {
        return teamScores;
    }

    public List<KillRecord> killLog() {
        return killLog;
    }

    public List<DamageEvent> damageEvents() {
        return damageEvents;
    }

    public Map<Integer, List<DamageEvent>> damageByAggressor() {
        return damageByAggressor;
    }

    public List<ChatEvent> chatLog() {
        return chatLog;
    }

    public List<ConsumableEvent> consumableLog() {
        return consumableLog;
    }

    public List<CapturePointState> capturePoints() {
        return capturePoints;
    }

    public Map<Integer, BuffZoneState> buffZones() {
        return buffZones;
    }

    public List<WeatherZoneState> weatherZones() {
        return weatherZones;
    }

    public List<BuildingState> buildings() {
        return buildings;
    }

    public List<DeadShipRecord> deadShips() {
        return deadShips;
    }

    public List<CapturedBuff> capturedBuffs() {
        return capturedBuffs;
    }

    public List<ArtillerySalvo> firedSalvos() {
        return firedSalvos;
    }

    public List<TorpedoRecord> torpedoes() {
        return torpedoes;
    }

    public Map<Long, TorpedoRecord> activeTorpedoes() {
        return activeTorpedoes;
    }

    private static long torpedoKey(int ownerId, int shotId) {
        return ((long) ownerId << 32) | (shotId & 0xFFFFFFFFL);
    }

    public List<ShotHitRecord> shotHits() {
        return shotHits;
    }

    public List<PlaneRecord> planeEvents() {
        return planeEvents;
    }

    public Map<Long, PlaneState> activePlanes() {
        return activePlanes;
    }

    public Map<Long, WardState> activeWards() {
        return activeWards;
    }

    public List<VoiceLineEvent> voiceLineLog() {
        return voiceLineLog;
    }

    public List<RibbonEvent> ribbonLog() {
        return ribbonLog;
    }

    public Set<String> entityTypes() {
        return entityTypes;
    }

    public Map<Integer, EntityState> smokeScreens() {
        return smokeScreens;
    }

    public int battleStageId() {
        return battleStageId;
    }

    public long teamWinScore() {
        return teamWinScore;
    }

    public long holdReward() {
        return holdReward;
    }

    public float holdPeriod() {
        return holdPeriod;
    }

    public List<Integer> holdCpIndices() {
        return holdCpIndices;
    }

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

    private void ensureTeamScore(int index) {
        while (teamScores.size() <= index) {
            teamScores.add(new TeamScore(teamScores.size(), 0));
        }
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
        if (h instanceof ArgValue.FloatVal(double value3)) es.health = (float) value3;
        else if (h instanceof ArgValue.IntVal(long value)) es.health = (float) value;

        ArgValue mh = props.get("maxHealth");
        if (mh instanceof ArgValue.FloatVal(double value2)) es.maxHealth = (float) value2;
        else if (mh instanceof ArgValue.IntVal(long value)) es.maxHealth = (float) value;

        ArgValue alive = props.get("isAlive");
        if (alive instanceof ArgValue.IntVal(long value1)) es.isAlive = value1 != 0;
        else if (alive instanceof ArgValue.BoolVal(boolean value)) es.isAlive = value;
    }

    private void extractTeam(Map<String, ArgValue> props, int eid) {
        ArgValue t = props.get("teamId");
        if (t instanceof ArgValue.IntVal(long value)) {
            getOrCreateEntity(eid, null).teamId = (int) value;
        }
    }

    private void applyCpDict(CapturePointState s, Map<String, ArgValue> dict) {
        ArgValue v;
        v = dict.get("hasInvaders");
        if (v instanceof ArgValue.IntVal(long value4)) s.hasInvaders = value4 != 0;
        v = dict.get("invaderTeam");
        if (v instanceof ArgValue.IntVal(long value3)) s.invaderTeam = (int) value3;
        v = dict.get("progress");
        if (v instanceof ArgValue.FloatVal(double value2)) s.progress = (float) value2;
        else if (v instanceof ArgValue.ArrayVal(List<ArgValue> elements) && elements.size() >= 2) {
            s.progress = floatFromArg(elements.getFirst());
        }
        v = dict.get("bothInside");
        if (v instanceof ArgValue.IntVal(long value1)) s.bothInside = value1 != 0;
        v = dict.get("isEnabled");
        if (v instanceof ArgValue.IntVal(long value)) s.isEnabled = value != 0;
    }

    // ── Static helpers ─────────────────────────────────────────────────

    /** 位置 x，null 安全（无位置视为 0）。 */
    static float posX(Vec3 p) { return p != null ? p.x() : 0f; }

    /** 位置 z，null 安全（无位置视为 0）。 */
    static float posZ(Vec3 p) { return p != null ? p.z() : 0f; }

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
        return v instanceof ArgValue.IntVal(long value) ? value : 0;
    }

    static float getFloatProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v != null ? floatFromArg(v) : 0f;
    }

    static boolean getBoolProp(Map<String, ArgValue> props, String key, boolean def) {
        ArgValue v = props.get(key);
        if (v instanceof ArgValue.BoolVal(boolean value1)) return value1;
        if (v instanceof ArgValue.IntVal(long value)) return value != 0;
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
}
