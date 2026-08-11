package com.shinoaki.wowsreplay.core.decode;

import com.shinoaki.wowsreplay.core.model.AccountId;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.pickle.PickleReader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player state decoded from {@code onArenaStateReceived} pickle blobs.
 *
 * <p>Mirrors Rust {@code PlayerStateData} with version-aware key maps.
 * The client serializes player data as a FixedDict where keys are integer
 * indices ordered alphabetically by field name. The indices change across
 * versions as WG adds/removes fields.</p>
 *
 * <p>Supported version layouts:</p>
 * <ul>
 *   <li>0.11.11+ — 38 fields (added keyTargetMarkers)</li>
 *   <li>0.10.9–0.11.10 — 37 fields (added antiAbuseEnabled, shipComponents)</li>
 *   <li>0.10.7–0.10.8 — 35 fields (added isClientLoaded)</li>
 *   <li>pre-0.10.7 — 34 fields</li>
 * </ul>
 */
public final class PlayerStateData {

    // ── Field name constants ────────────────────────────────────────────
    private static final String KEY_ACCOUNT_DBID       = "accountDBID";
    private static final String KEY_ANTI_ABUSE_ENABLED = "antiAbuseEnabled";
    private static final String KEY_AVATAR_ID          = "avatarId";
    private static final String KEY_CAMOUFLAGE_INFO    = "camouflageInfo";
    private static final String KEY_CLAN_COLOR         = "clanColor";
    private static final String KEY_CLAN_ID            = "clanID";
    private static final String KEY_CLAN_TAG           = "clanTag";
    private static final String KEY_CREW_PARAMS        = "crewParams";
    private static final String KEY_DOG_TAG            = "dogTag";
    private static final String KEY_FRAGS_COUNT        = "fragsCount";
    private static final String KEY_FRIENDLY_FIRE_ENABLED = "friendlyFireEnabled";
    public  static final String KEY_ID                 = "id";
    private static final String KEY_INVITATIONS_ENABLED = "invitationsEnabled";
    private static final String KEY_IS_ABUSER          = "isAbuser";
    private static final String KEY_IS_ALIVE           = "isAlive";
    private static final String KEY_IS_BOT             = "isBot";
    private static final String KEY_IS_CLIENT_LOADED   = "isClientLoaded";
    private static final String KEY_IS_CONNECTED       = "isConnected";
    private static final String KEY_IS_HIDDEN          = "isHidden";
    private static final String KEY_IS_LEAVER          = "isLeaver";
    private static final String KEY_IS_PRE_BATTLE_OWNER = "isPreBattleOwner";
    private static final String KEY_IS_T_SHOOTER       = "isTShooter";
    private static final String KEY_KEY_TARGET_MARKERS = "keyTargetMarkers";
    private static final String KEY_KILLED_BUILDINGS_COUNT = "killedBuildingsCount";
    private static final String KEY_MAX_HEALTH         = "maxHealth";
    private static final String KEY_NAME               = "name";
    private static final String KEY_PLAYER_MODE        = "playerMode";
    private static final String KEY_PRE_BATTLE_ID_ON_START = "preBattleIdOnStart";
    private static final String KEY_PRE_BATTLE_SIGN    = "preBattleSign";
    private static final String KEY_PREBATTLE_ID       = "prebattleId";
    private static final String KEY_REALM              = "realm";
    private static final String KEY_SHIP_COMPONENTS    = "shipComponents";
    private static final String KEY_SHIP_CONFIG_DUMP   = "shipConfigDump";
    private static final String KEY_SHIP_ID            = "shipId";
    private static final String KEY_SHIP_PARAMS_ID     = "shipParamsId";
    private static final String KEY_SKIN_ID            = "skinId";
    private static final String KEY_TEAM_ID            = "teamId";
    private static final String KEY_TTK_STATUS         = "ttkStatus";

    // ── Parsed fields ──────────────────────────────────────────────────
    private String  username    = "";
    private String  clan        = "";
    private long    clanId;
    private long    clanColor;
    /** 账号 ID —— 玩家 dict 的 accountDBID 字段（key 0）。 */
    private long    dbId;
    private String  realm;
    /** 战斗内 meta id —— 玩家 dict 的 id 字段（key 11），与 meta.vehicles[].id 同空间。 */
    private long    metaShipId;
    private int     entityId;
    private long    teamId      = -1;
    private long    maxHealth;
    private boolean isAbuser;
    private boolean isHidden;
    private boolean isBot;
    private boolean isAlive     = true;
    private Long    avatarId;
    private long    prebattleId;
    private boolean isConnected;
    private boolean isClientLoaded;
    private byte[]  shipConfigDump;
    private long    shipParamsId;

    /** All raw key→value entries (for diagnostics). */
    private final Map<Long, Object> raw = new LinkedHashMap<>();
    private Version version;

    // ── Constructors ───────────────────────────────────────────────────

    private PlayerStateData() {}

    /**
     * Parse from a pickle-decoded list of (key, value) tuples.
     * Each tuple is {@code [key: Long, value: Object]} where value
     * comes from {@link PickleReader}.
     *
     * @param tuples  list of (key, value) pairs from pickle
     * @param version game version for key layout selection
     * @param isBot   true if this is a bot player (different key layout)
     */
    public static PlayerStateData fromTuples(List<?> tuples, Version version, boolean isBot) {
        var rawValues = new LinkedHashMap<Long, Object>();
        for (var tupleObj : tuples) {
            if (!(tupleObj instanceof List<?> kv) || kv.size() < 2) continue;
            var keyObj = kv.get(0);
            if (!(keyObj instanceof Long key)) continue;
            rawValues.put(key, kv.get(1));
        }
        return fromRawValues(rawValues, version, isBot);
    }

    static PlayerStateData fromRawValues(Map<Long, Object> rawValues, Version version, boolean isBot) {
        var psd = new PlayerStateData();
        psd.version = version;
        var keyMap = isBot ? botKeyMap(version) : playerKeyMap(version);

        // Copy raw values
        psd.raw.putAll(rawValues);

        // Extract known fields by name
        // 15.x：accountDBID（key 0）才是真正的账号 ID（db_id）；
        // id 字段（key 11）是战斗内 meta id（meta_ship_id），与 meta.vehicles[].id 同空间，
        // 用来把 meta 战舰映射到战斗内玩家（m.id() == player.meta_ship_id()）。
        psd.dbId        = getLong(rawValues, keyMap, KEY_ACCOUNT_DBID);
        psd.metaShipId  = getLong(rawValues, keyMap, KEY_ID);
        psd.username    = getString(rawValues, keyMap, KEY_NAME);
        psd.clan        = getString(rawValues, keyMap, KEY_CLAN_TAG);
        psd.clanId      = getLong(rawValues, keyMap, KEY_CLAN_ID);
        psd.clanColor   = getLong(rawValues, keyMap, KEY_CLAN_COLOR);
        psd.realm       = getStringOrNull(rawValues, keyMap, KEY_REALM);
        psd.entityId    = (int) getLong(rawValues, keyMap, KEY_SHIP_ID);
        psd.teamId      = getLong(rawValues, keyMap, KEY_TEAM_ID);
        psd.maxHealth   = getLong(rawValues, keyMap, KEY_MAX_HEALTH);
        psd.isAbuser    = getBool(rawValues, keyMap, KEY_IS_ABUSER);
        psd.isHidden    = getBool(rawValues, keyMap, KEY_IS_HIDDEN);
        psd.isBot       = getBool(rawValues, keyMap, KEY_IS_BOT);
        psd.isAlive     = getBoolOr(rawValues, keyMap, KEY_IS_ALIVE, true);
        psd.shipParamsId = getLong(rawValues, keyMap, KEY_SHIP_PARAMS_ID);

        // Human-only fields
        if (hasKey(keyMap, KEY_AVATAR_ID)) {
            long avId = getLong(rawValues, keyMap, KEY_AVATAR_ID);
            if (avId > 0) psd.avatarId = avId;
        }
        if (hasKey(keyMap, KEY_PREBATTLE_ID)) {
            psd.prebattleId = getLong(rawValues, keyMap, KEY_PREBATTLE_ID);
        }
        if (hasKey(keyMap, KEY_IS_CONNECTED)) {
            psd.isConnected = getBool(rawValues, keyMap, KEY_IS_CONNECTED);
        }
        if (hasKey(keyMap, KEY_IS_CLIENT_LOADED)) {
            psd.isClientLoaded = getBool(rawValues, keyMap, KEY_IS_CLIENT_LOADED);
        }

        // shipConfigDump: array of u8 encoded as List<Long>
        if (hasKey(keyMap, KEY_SHIP_CONFIG_DUMP)) {
            var idx = keyMap.get(KEY_SHIP_CONFIG_DUMP);
            if (idx != null && idx < rawValues.size()) {
                var val = getByIndex(rawValues, idx);
                if (val instanceof List<?> arr) {
                    byte[] bytes = new byte[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        var elem = arr.get(i);
                        bytes[i] = (elem instanceof Number n) ? n.byteValue() : 0;
                    }
                    psd.shipConfigDump = bytes;
                }
            }
        }

        return psd;
    }

    // ── Key maps ───────────────────────────────────────────────────────

    /**
     * Player key map for versions ≥ 0.11.11 (38 fields).
     * Indices are derived from alphabetical sort of FixedDict field names.
     */
    private static Map<String, Integer> keyMap38() {
        var m = new LinkedHashMap<String, Integer>();
        m.put(KEY_ACCOUNT_DBID, 0);
        m.put(KEY_ANTI_ABUSE_ENABLED, 1);
        m.put(KEY_AVATAR_ID, 2);
        m.put(KEY_CAMOUFLAGE_INFO, 3);
        m.put(KEY_CLAN_COLOR, 4);
        m.put(KEY_CLAN_ID, 5);
        m.put(KEY_CLAN_TAG, 6);
        m.put(KEY_CREW_PARAMS, 7);
        m.put(KEY_DOG_TAG, 8);
        m.put(KEY_FRAGS_COUNT, 9);
        m.put(KEY_FRIENDLY_FIRE_ENABLED, 10);
        m.put(KEY_ID, 11);
        m.put(KEY_INVITATIONS_ENABLED, 12);
        m.put(KEY_IS_ABUSER, 13);
        m.put(KEY_IS_ALIVE, 14);
        m.put(KEY_IS_BOT, 15);
        m.put(KEY_IS_CLIENT_LOADED, 16);
        m.put(KEY_IS_CONNECTED, 17);
        m.put(KEY_IS_HIDDEN, 18);
        m.put(KEY_IS_LEAVER, 19);
        m.put(KEY_IS_PRE_BATTLE_OWNER, 20);
        m.put(KEY_IS_T_SHOOTER, 21);
        m.put(KEY_KEY_TARGET_MARKERS, 22);
        m.put(KEY_KILLED_BUILDINGS_COUNT, 23);
        m.put(KEY_MAX_HEALTH, 24);
        m.put(KEY_NAME, 25);
        m.put(KEY_PLAYER_MODE, 26);
        m.put(KEY_PRE_BATTLE_ID_ON_START, 27);
        m.put(KEY_PRE_BATTLE_SIGN, 28);
        m.put(KEY_PREBATTLE_ID, 29);
        m.put(KEY_REALM, 30);
        m.put(KEY_SHIP_COMPONENTS, 31);
        m.put(KEY_SHIP_CONFIG_DUMP, 32);
        m.put(KEY_SHIP_ID, 33);
        m.put(KEY_SHIP_PARAMS_ID, 34);
        m.put(KEY_SKIN_ID, 35);
        m.put(KEY_TEAM_ID, 36);
        m.put(KEY_TTK_STATUS, 37);
        return m;
    }

    /** 0.10.9–0.11.10: 37 fields (added antiAbuseEnabled, shipComponents; no keyTargetMarkers) */
    private static Map<String, Integer> keyMap37() {
        var m = new LinkedHashMap<String, Integer>();
        m.put(KEY_ACCOUNT_DBID, 0);
        m.put(KEY_ANTI_ABUSE_ENABLED, 1);
        m.put(KEY_AVATAR_ID, 2);
        m.put(KEY_CAMOUFLAGE_INFO, 3);
        m.put(KEY_CLAN_COLOR, 4);
        m.put(KEY_CLAN_ID, 5);
        m.put(KEY_CLAN_TAG, 6);
        m.put(KEY_CREW_PARAMS, 7);
        m.put(KEY_DOG_TAG, 8);
        m.put(KEY_FRAGS_COUNT, 9);
        m.put(KEY_FRIENDLY_FIRE_ENABLED, 10);
        m.put(KEY_ID, 11);
        m.put(KEY_INVITATIONS_ENABLED, 12);
        m.put(KEY_IS_ABUSER, 13);
        m.put(KEY_IS_ALIVE, 14);
        m.put(KEY_IS_BOT, 15);
        m.put(KEY_IS_CLIENT_LOADED, 16);
        m.put(KEY_IS_CONNECTED, 17);
        m.put(KEY_IS_HIDDEN, 18);
        m.put(KEY_IS_LEAVER, 19);
        m.put(KEY_IS_PRE_BATTLE_OWNER, 20);
        m.put(KEY_IS_T_SHOOTER, 21);
        m.put(KEY_KILLED_BUILDINGS_COUNT, 22);
        m.put(KEY_MAX_HEALTH, 23);
        m.put(KEY_NAME, 24);
        m.put(KEY_PLAYER_MODE, 25);
        m.put(KEY_PRE_BATTLE_ID_ON_START, 26);
        m.put(KEY_PRE_BATTLE_SIGN, 27);
        m.put(KEY_PREBATTLE_ID, 28);
        m.put(KEY_REALM, 29);
        m.put(KEY_SHIP_COMPONENTS, 30);
        m.put(KEY_SHIP_CONFIG_DUMP, 31);
        m.put(KEY_SHIP_ID, 32);
        m.put(KEY_SHIP_PARAMS_ID, 33);
        m.put(KEY_SKIN_ID, 34);
        m.put(KEY_TEAM_ID, 35);
        m.put(KEY_TTK_STATUS, 36);
        return m;
    }

    /** 0.10.7–0.10.8: 35 fields (added isClientLoaded; no antiAbuseEnabled/shipComponents) */
    private static Map<String, Integer> keyMap35() {
        var m = new LinkedHashMap<String, Integer>();
        m.put(KEY_ACCOUNT_DBID, 0);
        m.put(KEY_AVATAR_ID, 1);
        m.put(KEY_CAMOUFLAGE_INFO, 2);
        m.put(KEY_CLAN_COLOR, 3);
        m.put(KEY_CLAN_ID, 4);
        m.put(KEY_CLAN_TAG, 5);
        m.put(KEY_CREW_PARAMS, 6);
        m.put(KEY_DOG_TAG, 7);
        m.put(KEY_FRAGS_COUNT, 8);
        m.put(KEY_FRIENDLY_FIRE_ENABLED, 9);
        m.put(KEY_ID, 10);
        m.put(KEY_INVITATIONS_ENABLED, 11);
        m.put(KEY_IS_ABUSER, 12);
        m.put(KEY_IS_ALIVE, 13);
        m.put(KEY_IS_BOT, 14);
        m.put(KEY_IS_CLIENT_LOADED, 15);
        m.put(KEY_IS_CONNECTED, 16);
        m.put(KEY_IS_HIDDEN, 17);
        m.put(KEY_IS_LEAVER, 18);
        m.put(KEY_IS_PRE_BATTLE_OWNER, 19);
        m.put(KEY_IS_T_SHOOTER, 20);
        m.put(KEY_KILLED_BUILDINGS_COUNT, 21);
        m.put(KEY_MAX_HEALTH, 22);
        m.put(KEY_NAME, 23);
        m.put(KEY_PLAYER_MODE, 24);
        m.put(KEY_PRE_BATTLE_ID_ON_START, 25);
        m.put(KEY_PRE_BATTLE_SIGN, 26);
        m.put(KEY_PREBATTLE_ID, 27);
        m.put(KEY_REALM, 28);
        m.put(KEY_SHIP_CONFIG_DUMP, 29);
        m.put(KEY_SHIP_ID, 30);
        m.put(KEY_SHIP_PARAMS_ID, 31);
        m.put(KEY_SKIN_ID, 32);
        m.put(KEY_TEAM_ID, 33);
        m.put(KEY_TTK_STATUS, 34);
        return m;
    }

    /** pre-0.10.7: 34 fields */
    private static Map<String, Integer> keyMap34() {
        var m = new LinkedHashMap<String, Integer>();
        m.put(KEY_ACCOUNT_DBID, 0);
        m.put(KEY_AVATAR_ID, 1);
        m.put(KEY_CAMOUFLAGE_INFO, 2);
        m.put(KEY_CLAN_COLOR, 3);
        m.put(KEY_CLAN_ID, 4);
        m.put(KEY_CLAN_TAG, 5);
        m.put(KEY_CREW_PARAMS, 6);
        m.put(KEY_DOG_TAG, 7);
        m.put(KEY_FRAGS_COUNT, 8);
        m.put(KEY_FRIENDLY_FIRE_ENABLED, 9);
        m.put(KEY_ID, 10);
        m.put(KEY_INVITATIONS_ENABLED, 11);
        m.put(KEY_IS_ABUSER, 12);
        m.put(KEY_IS_ALIVE, 13);
        m.put(KEY_IS_BOT, 14);
        m.put(KEY_IS_CONNECTED, 15);
        m.put(KEY_IS_HIDDEN, 16);
        m.put(KEY_IS_LEAVER, 17);
        m.put(KEY_IS_PRE_BATTLE_OWNER, 18);
        m.put(KEY_IS_T_SHOOTER, 19);
        m.put(KEY_KILLED_BUILDINGS_COUNT, 20);
        m.put(KEY_MAX_HEALTH, 21);
        m.put(KEY_NAME, 22);
        m.put(KEY_PLAYER_MODE, 23);
        m.put(KEY_PRE_BATTLE_ID_ON_START, 24);
        m.put(KEY_PRE_BATTLE_SIGN, 25);
        m.put(KEY_PREBATTLE_ID, 26);
        m.put(KEY_REALM, 27);
        m.put(KEY_SHIP_CONFIG_DUMP, 28);
        m.put(KEY_SHIP_ID, 29);
        m.put(KEY_SHIP_PARAMS_ID, 30);
        m.put(KEY_SKIN_ID, 31);
        m.put(KEY_TEAM_ID, 32);
        m.put(KEY_TTK_STATUS, 33);
        return m;
    }

    /** Bot key map for 0.12.8+ (28 fields, different layout from players) */
    private static Map<String, Integer> botKeyMap28() {
        var m = new LinkedHashMap<String, Integer>();
        m.put(KEY_ACCOUNT_DBID, 0);
        m.put(KEY_ANTI_ABUSE_ENABLED, 1);
        m.put(KEY_CAMOUFLAGE_INFO, 2);
        m.put(KEY_CLAN_COLOR, 3);
        m.put(KEY_CLAN_ID, 4);
        m.put(KEY_CLAN_TAG, 5);
        m.put(KEY_CREW_PARAMS, 6);
        m.put(KEY_DOG_TAG, 7);
        m.put(KEY_FRAGS_COUNT, 8);
        m.put(KEY_FRIENDLY_FIRE_ENABLED, 9);
        m.put(KEY_ID, 10);
        m.put(KEY_IS_ABUSER, 11);
        m.put(KEY_IS_ALIVE, 12);
        m.put(KEY_IS_BOT, 13);
        m.put(KEY_IS_HIDDEN, 14);
        m.put(KEY_IS_T_SHOOTER, 15);
        m.put(KEY_KEY_TARGET_MARKERS, 16);
        m.put(KEY_KILLED_BUILDINGS_COUNT, 17);
        m.put(KEY_MAX_HEALTH, 18);
        m.put(KEY_NAME, 19);
        m.put(KEY_REALM, 20);
        m.put(KEY_SHIP_COMPONENTS, 21);
        m.put(KEY_SHIP_CONFIG_DUMP, 22);
        m.put(KEY_SHIP_ID, 23);
        m.put(KEY_SHIP_PARAMS_ID, 24);
        m.put(KEY_SKIN_ID, 25);
        m.put(KEY_TEAM_ID, 26);
        m.put(KEY_TTK_STATUS, 27);
        return m;
    }

    static Map<String, Integer> playerKeyMap(Version version) {
        if (version == null) return keyMap38(); // default to latest
        if (version.isAtLeast(new Version(0, 11, 11, 0))) return keyMap38();
        if (version.isAtLeast(new Version(0, 10, 9, 0)))  return keyMap37();
        if (version.isAtLeast(new Version(0, 10, 7, 0)))  return keyMap35();
        return keyMap34();
    }

    static Map<String, Integer> botKeyMap(Version version) {
        if (version != null && version.isAtLeast(new Version(0, 12, 8, 0))) {
            return botKeyMap28();
        }
        // Older versions: bots use same layout as players
        return playerKeyMap(version);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static long getLong(Map<Long, Object> raw, Map<String, Integer> keyMap, String key) {
        var idx = keyMap.get(key);
        if (idx == null) return 0;
        var val = getByIndex(raw, idx);
        if (val instanceof Long l) return l;
        if (val instanceof Double d) return d.longValue();
        if (val instanceof Number n) return n.longValue();
        return 0;
    }

    private static String getString(Map<Long, Object> raw, Map<String, Integer> keyMap, String key) {
        var idx = keyMap.get(key);
        if (idx == null) return "";
        var val = getByIndex(raw, idx);
        return val instanceof String s ? s : "";
    }

    private static String getStringOrNull(Map<Long, Object> raw, Map<String, Integer> keyMap, String key) {
        var s = getString(raw, keyMap, key);
        return s.isEmpty() ? null : s;
    }

    private static boolean getBool(Map<Long, Object> raw, Map<String, Integer> keyMap, String key) {
        return getBoolOr(raw, keyMap, key, false);
    }

    private static boolean getBoolOr(Map<Long, Object> raw, Map<String, Integer> keyMap, String key, boolean def) {
        var idx = keyMap.get(key);
        if (idx == null) return def;
        var val = getByIndex(raw, idx);
        if (val instanceof Boolean b) return b;
        if (val instanceof Long l) return l != 0;
        if (val instanceof Number n) return n.longValue() != 0;
        return def;
    }

    private static boolean hasKey(Map<String, Integer> keyMap, String key) {
        return keyMap.containsKey(key);
    }

    /** Get value by integer index from raw map (which is keyed by Long indices). */
    private static Object getByIndex(Map<Long, Object> raw, int idx) {
        return raw.get((long) idx);
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public String username()       { return username; }
    public String clan()           { return clan; }
    public long clanId()           { return clanId; }
    public long clanColor()        { return clanColor; }
    /** 账号 ID（取自 accountDBID，key 0）。 */
    public long dbId()             { return dbId; }
    public String realm()          { return realm; }
    /** 战斗内 meta id（取自 id 字段，key 11）——与 meta.vehicles[].id 同空间。 */
    public long metaShipId()       { return metaShipId; }
    public int entityId()          { return entityId; }
    public long teamId()           { return teamId; }
    public long maxHealth()        { return maxHealth; }
    public boolean isAbuser()      { return isAbuser; }
    public boolean isHidden()      { return isHidden; }
    public boolean isBot()         { return isBot; }
    public boolean isAlive()       { return isAlive; }
    public Long avatarId()         { return avatarId; }
    public long prebattleId()      { return prebattleId; }
    public boolean isConnected()   { return isConnected; }
    public boolean isClientLoaded(){ return isClientLoaded; }
    public byte[] shipConfigDump() { return shipConfigDump; }
    public long shipParamsId()     { return shipParamsId; }
    public Map<Long, Object> raw() 
{ return Collections.unmodifiableMap(raw); }

    /** 原始 pickle 字段名→值映射（dumper 输出 initial_state.raw_with_names 用）。 */
    public Map<String, Object> rawWithNames() {
        var keyMap = isBot() ? botKeyMap(version) : playerKeyMap(version);
        var nameByIndex = new LinkedHashMap<Integer, String>();
        for (var e : keyMap.entrySet()) nameByIndex.put(e.getValue(), e.getKey());
        var out = new LinkedHashMap<String, Object>();
        for (var e : raw.entrySet()) {
            var name = nameByIndex.get(e.getKey().intValue());
            if (name != null) {
                // shipConfigDump 是二进制 blob（BINSTRING 按 ISO-8859-1 解成 String），直接输出 hex
                out.put(name, KEY_SHIP_CONFIG_DUMP.equals(name) ? toHex(e.getValue()) : e.getValue());
            }
        }
        return out;
    }

    /** 字节型值 → 小写 hex 字符串（String=Latin-1 字节 / byte[] / List<Number> 均可）。 */
    private static String toHex(Object rawValue) {
        var sb = new StringBuilder();
        if (rawValue instanceof byte[] arr) {
            for (byte b : arr) appendHexByte(sb, b & 0xFF);
        } else if (rawValue instanceof String s) {
            for (int i = 0; i < s.length(); i++) appendHexByte(sb, s.charAt(i));
        } else if (rawValue instanceof List<?> list) {
            for (var e : list) {
                if (e instanceof Number n) appendHexByte(sb, n.intValue() & 0xFF);
            }
        } else {
            return String.valueOf(rawValue);
        }
        return sb.toString();
    }

    private static void appendHexByte(StringBuilder sb, int b) {
        sb.append(HEX_DIGITS[(b >>> 4) & 0xF]).append(HEX_DIGITS[b & 0xF]);
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** 账号 ID（AccountId，取自 accountDBID）。 */
    public AccountId accountId()   { return new AccountId((int) dbId); }
    public EntityId shipEntityId() { return new EntityId(entityId); }

    @Override
    public String toString() {
        return "PlayerStateData[" + username + " db=" + dbId + " entity=" + entityId
            + " team=" + teamId + " metaShip=" + metaShipId
            + " bot=" + isBot + " hp=" + maxHealth + "]";
    }
}
