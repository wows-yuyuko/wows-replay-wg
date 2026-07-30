package com.wows.replay.core;

import com.wows.replay.core.types.Version;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Packet type identifier from the BigWorld replay protocol.
 *
 * <p>Version-aware: before 12.6.0 the wire layout had no BattleResults packet
 * and ran each subsequent ID one slot lower. {@link #fromRaw(int, Version)}
 * dispatches correctly across the layout shift.</p>
 *
 * <p>Fail-open: any unrecognized wire value becomes {@link #UNKNOWN} carrying
 * the raw int, so a new game version never breaks the walker.</p>
 */
public enum PacketTypeId {

    // --- Modern layout (≥ 12.6.0) ---
    BASE_PLAYER_CREATE(0x00),
    CELL_PLAYER_CREATE(0x01),
    ENTITY_CONTROL(0x02),
    ENTITY_ENTER(0x03),
    ENTITY_LEAVE(0x04),
    ENTITY_CREATE(0x05),
    ENTITY_PROPERTY(0x07),
    ENTITY_METHOD(0x08),
    POSITION(0x0a),
    SERVER_TICK(0x0e),
    SERVER_TIMESTAMP(0x0f),
    INIT_FLAG(0x10),
    INIT_MARKER(0x13),
    VERSION(0x16),
    GUN_MARKER(0x18),
    PLAYER_NET_STATS(0x1d),
    OWN_SHIP(0x20),
    BATTLE_RESULTS(0x22),
    NESTED_PROPERTY_UPDATE(0x23),
    CAMERA(0x25),
    BASE_PLAYER_CREATE_STUB(0x26),
    CAMERA_MODE(0x27),
    MAP(0x28),
    NON_VOLATILE_POSITION(0x2a),
    PLAYER_ORIENTATION(0x2c),
    /**
     * Unknown packet type introduced by WG after 15.0.
     *
     * <p>Payload is always 8 bytes: {@code [entity_id: u32 LE][value: u32 LE]}.
     * Not yet seen in wows-toolkit or replays_unpack mappings.
     * Tentatively decoded as BLOB until the semantics are identified.</p>
     */
    UNKNOWN_0X2E(0x2e),
    CAMERA_FREE_LOOK(0x2f),
    SET_WEAPON_LOCK(0x30),
    SUB_CONTROLLER(0x31),
    CRUISE_STATE(0x32),
    SHOT_TRACKING(0x33),
    ;

    // Wire values below 0x22 are layout-invariant, so a single lookup table works for both.
    // Legacy-only IDs (not in modern enum) are handled via the legacy override table.
    private static final Map<Integer, PacketTypeId> MODERN_BY_RAW =
        Stream.of(values()).collect(Collectors.toUnmodifiableMap(PacketTypeId::raw, Function.identity()));

    // Legacy layout overrides — only entries that differ from the modern layout.
    private static final Map<Integer, PacketTypeId> LEGACY_OVERRIDES = Map.ofEntries(
        Map.entry(0x22, NESTED_PROPERTY_UPDATE),  // no BATTLE_RESULTS in legacy
        Map.entry(0x24, CAMERA),
        Map.entry(0x27, MAP),
        Map.entry(0x29, NON_VOLATILE_POSITION),
        Map.entry(0x2b, PLAYER_ORIENTATION),
        Map.entry(0x2e, CAMERA_FREE_LOOK),
        Map.entry(0x2f, SET_WEAPON_LOCK),
        Map.entry(0x30, SUB_CONTROLLER),
        Map.entry(0x31, CRUISE_STATE),
        Map.entry(0x32, SHOT_TRACKING)
    );

    /** Minimum version for the modern packet-ID layout. */
    public static final Version MODERN_LAYOUT_MIN_VERSION = new Version(12, 6, 0, 0);

    private final int raw;

    PacketTypeId(int raw) {
        this.raw = raw;
    }

    /** The wire byte for this packet type. */
    public int raw() { return raw; }

    /** Human-readable name for debugging. */
    public String displayName() { return name().toLowerCase().replace('_', ' '); }

    /**
     * Map a raw wire identifier for the given game version.
     *
     * @param raw     the u32 packet type from the wire
     * @param version the replay's game version (for layout selection)
     * @return the mapped packet type, or null if unknown (caller converts to UNKNOWN)
     */
    public static PacketTypeId fromRaw(int raw, Version version) {
        if (version == null) return fromRawModern(raw);
        if (version.compareTo(MODERN_LAYOUT_MIN_VERSION) >= 0) {
            return fromRawModern(raw);
        } else {
            return fromRawLegacy(raw);
        }
    }

    /**
     * Map assuming the modern layout (used when version is unknown).
     */
    public static PacketTypeId fromRawModern(int raw) {
        return MODERN_BY_RAW.getOrDefault(raw, null);
    }

    /**
     * Map using the legacy (pre‑12.6.0) layout.
     */
    public static PacketTypeId fromRawLegacy(int raw) {
        // Check legacy overrides first
        var legacy = LEGACY_OVERRIDES.get(raw);
        if (legacy != null) return legacy;
        // Fall back to modern table (all pre-shift IDs are invariant)
        return MODERN_BY_RAW.get(raw);
    }
}
