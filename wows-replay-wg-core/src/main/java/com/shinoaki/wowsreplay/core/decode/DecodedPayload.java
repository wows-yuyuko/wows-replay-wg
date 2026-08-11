package com.shinoaki.wowsreplay.core.decode;

import com.shinoaki.wowsreplay.core.model.*;
import com.shinoaki.wowsreplay.core.packet.*;
import com.shinoaki.wowsreplay.core.types.ArgValue;

import java.util.List;
import java.util.Map;

/**
 * Layer-2 decoded packet payload — semantic interpretation of raw packet data.
 *
 * <p>Mirrors Rust {@code DecodedPacketPayload}. The {@link PacketDecoder} produces
 * these from {@link Parser} output, converting EntityMethod calls into
 * typed domain events and parsing pickle blobs into structured data.</p>
 */
public sealed interface DecodedPayload {

    // ── Chat / Voice ───────────────────────────────────────────────────────

    record ChatMessagePayload(
        EntityId entityId,
        AccountId senderId,
        String audience,
        String message,
        ChatExtra extraData
    ) implements DecodedPayload {}

    record ChatExtra(long preBattleSign, long preBattleId, String playerClanTag,
                     long type, EntityId playerAvatarId, String playerName) {}

    record VoiceLinePayload(AccountId senderId, boolean isGlobal, String message) implements DecodedPayload {}

    record RibbonPayload(int ribbonId) implements DecodedPayload {}

    // ── Position ───────────────────────────────────────────────────────────

    record PositionPayload(PositionPacket packet) implements DecodedPayload {}
    record PlayerOrientationPayload(PlayerOrientationPacket packet) implements DecodedPayload {}
    record NonVolatilePositionPayload(NonVolatilePositionPacket packet) implements DecodedPayload {}

    // ── Damage / Combat ────────────────────────────────────────────────────

    /** Cumulative damage stat entry from receiveDamageStat. */
    record DamageStatEntry(long weaponId, long categoryId, long count, double total) {}

    record DamageStatPayload(List<DamageStatEntry> entries) implements DecodedPayload {}

    record DamageReceivedEntry(EntityId aggressor, float damage) {}

    record DamageReceivedPayload(EntityId victim, List<DamageReceivedEntry> aggressors) implements DecodedPayload {}

    /** receiveVehicleDeath — killer, victim entity IDs + death cause code. */
    record ShipDestroyedPayload(EntityId killer, EntityId victim, int cause) implements DecodedPayload {}

    record HitType(int collisionId, int shellHitId, int raw) {}

    record TerminalBallistics(Vec3 position, Vec3 velocity, boolean detonatorActivated, float materialAngle) {}

    record ShotHitEntry(EntityId ownerId, HitType hitType, int shotId, Vec3 position,
                        TerminalBallistics terminalBallistics) {}

    record ShotKillsPayload(AvatarId avatarId, List<ShotHitEntry> hits) implements DecodedPayload {}

    // ── Entity lifecycle (pass-through) ────────────────────────────────────

    record EntityMethodPayload(EntityMethodPacket packet) implements DecodedPayload {}
    /** Entity property decoded via {@link PropertyDecoder}. */
    record PropertyChangePayload(PropertyDecoder.PropertyChange change) implements DecodedPayload {}
    record BasePlayerCreatePayload(BasePlayerCreatePacket packet) implements DecodedPayload {}
    record CellPlayerCreatePayload(CellPlayerCreatePacket packet) implements DecodedPayload {}
    record EntityEnterPayload(EntityEnterPacket packet) implements DecodedPayload {}
    record EntityLeavePayload(EntityLeavePacket packet) implements DecodedPayload {}
    record EntityCreatePayload(EntityCreatePacket packet) implements DecodedPayload {}
    record EntityControlPayload(EntityControlPacket packet) implements DecodedPayload {}

    // ── Arena / Battle state ───────────────────────────────────────────────

    record OnArenaStateReceivedPayload(
        long arenaId,
        int teamBuildTypeId,
        Map<Long, List<Map<String, String>>> preBattlesInfo,
        List<PlayerStateData> playerStates,
        List<PlayerStateData> botStates
    ) implements DecodedPayload {}

    record OnGameRoomStateChangedPayload(List<Map<String, ArgValue>> playerStates) implements DecodedPayload {}

    record NewPlayerSpawnedInBattlePayload(
        List<PlayerStateData> playerStates,
        List<PlayerStateData> botStates
    ) implements DecodedPayload {}

    /** onBattleEnd: winning team index (may be null if unknown), finish type code. */
    record BattleEndPayload(Integer winningTeam, int finishType) implements DecodedPayload {}

    // ── Consumable ─────────────────────────────────────────────────────────

    /** 消耗品使用者类型：船体 / 飞机（中队）/ 其他。 */
    enum ConsumableKind { SHIP, PLANE, OTHER }

    /** consumableId == 0 means unknown/unrecognized. */
    record ConsumablePayload(EntityId entity, int consumableId,
                             float duration, Integer usageType) implements DecodedPayload {}

    /** CV 飞机（中队）消耗品：Avatar.squadronConsumableUsed(squadronId, usageParams[, workTimeLeft])。 */
    record SquadronConsumablePayload(EntityId entity, int squadronId, int consumableId,
                                     float duration, Integer usageType) implements DecodedPayload {}

    // ── Minimap ────────────────────────────────────────────────────────────

    record MinimapUpdateEntry(EntityId entityId, boolean isSentinel, boolean disappearing,
                              float heading, float x, float z, boolean visible,
                              int visibilityFlags) {}

    record MinimapUpdatePayload(List<MinimapUpdateEntry> updates) implements DecodedPayload {}

    // ── Artillery / Torpedo ───────────────────────────────────────────────

    record ArtilleryShotData(Vec3 origin, float pitch, float speed, Vec3 target,
                             int shotId, int gunBarrelId, float serverTimeLeft,
                             float shooterHeight, float hitDistance) {}

    record ArtillerySalvo(EntityId ownerId, GameParamId paramsId, int salvoId,
                          List<ArtilleryShotData> shots) {}

    record ArtilleryShotsPayload(AvatarId avatarId, List<ArtillerySalvo> salvos) implements DecodedPayload {}

    record TorpedoData(EntityId ownerId, GameParamId paramsId, int salvoId, int skinId,
                       int shotId, Vec3 origin, Vec3 direction, boolean armed) {}

    record TorpedoesReceivedPayload(AvatarId avatarId, List<TorpedoData> torpedoes) implements DecodedPayload {}

    record TorpedoDirectionPayload(EntityId ownerId, int shotId, Vec3 position,
                                   float targetYaw, float speedCoef) implements DecodedPayload {}

    // ── Gun sync / Ammo ───────────────────────────────────────────────────

    record GunSyncPayload(EntityId entityId, int weaponType, int gunId,
                          float yaw, float pitch) implements DecodedPayload {}

    record SetAmmoForWeaponPayload(EntityId entityId, int weaponType,
                                   GameParamId ammoParamId, boolean isReload) implements DecodedPayload {}

    // ── Aviation ──────────────────────────────────────────────────────────

    record PlaneAddedPayload(EntityId entityId, long planeId, int teamId,
                             GameParamId paramsId, float x, float z) implements DecodedPayload {}

    record WardAddedPayload(EntityId entityId, long planeId, Vec3 position,
                            float radius, EntityId ownerId) implements DecodedPayload {}

    record WardRemovedPayload(EntityId entityId, long planeId) implements DecodedPayload {}

    record PlaneRemovedPayload(EntityId entityId, long planeId) implements DecodedPayload {}

    record PlanePositionPayload(EntityId entityId, long planeId, float x, float z) implements DecodedPayload {}

    // ── Property update (pass-through) ─────────────────────────────────────

    record PropertyUpdatePayload(PropertyUpdatePacket packet) implements DecodedPayload {}

    // ── Simple pass-through packets ────────────────────────────────────────

    record MapPayload(MapPacket packet) implements DecodedPayload {}
    record VersionPayload(String version) implements DecodedPayload {}
    record CameraPayload(CameraPacket packet) implements DecodedPayload {}
    record CameraModePayload(int mode) implements DecodedPayload {}
    record CameraFreeLookPayload(boolean enabled) implements DecodedPayload {}
    record CruiseStatePayload(int state, int value) implements DecodedPayload {}
    record OwnShipPayload(OwnShipPacket packet) implements DecodedPayload {}
    record SetWeaponLockPayload(SetWeaponLockPacket packet) implements DecodedPayload {}
    record ServerTimestampPayload(double timestamp) implements DecodedPayload {}
    record ServerTickPayload(double tickRate) implements DecodedPayload {}
    record SubControllerPayload(SubControllerPacket packet) implements DecodedPayload {}
    record ShotTrackingPayload(ShotTrackingPacket packet) implements DecodedPayload {}
    record GunMarkerPayload(GunMarkerPacket packet) implements DecodedPayload {}
    record PlayerNetStatsPayload(PlayerNetStatsPacket packet) implements DecodedPayload {}
    record InitFlagPayload(int flag) implements DecodedPayload {}
    record InitMarkerPayload() implements DecodedPayload {}
    record BattleResultsPayload(String json) implements DecodedPayload {}

    // ── Fallback ──────────────────────────────────────────────────────────

    record UnknownPayload(byte[] raw) implements DecodedPayload {}
    record InvalidPayload(String reason) implements DecodedPayload {}
}
