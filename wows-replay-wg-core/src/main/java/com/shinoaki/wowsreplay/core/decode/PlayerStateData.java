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
 * <p>Mirrors Rust {@code PlayerStateData}. The client serializes player data
 * as a FixedDict where keys are integer indices ordered alphabetically by
 * field name. The indices change across versions as WG adds/removes fields,
 * so the field→index layout comes from {@code constants.json} 的
 * {@code PLAYER_NUM_MEMBER_MAP} / {@code BOT_NUM_MEMBER_MAP}（由调用方传入，
 * 见 {@code GameConstantsProvider#playerMemberIndices()}），不再硬编码版本表。</p>
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
    /** 字段名→索引布局（来自 constants.json NUM_MEMBER_MAP，解析时传入并保存供 rawWithNames 反查）。 */
    private Map<String, Integer> keyMap = Map.of();

    // ── Constructors ───────────────────────────────────────────────────

    private PlayerStateData() {}

    /**
     * Parse from a pickle-decoded list of (key, value) tuples.
     * Each tuple is {@code [key: Long, value: Object]} where value
     * comes from {@link PickleReader}.
     *
     * @param tuples  list of (key, value) pairs from pickle
     * @param version game version（记录用途）
     * @param isBot   true if this is a bot player (different key layout)
     * @param keyMap  field→index 布局（来自 constants.json NUM_MEMBER_MAP）
     */
    public static PlayerStateData fromTuples(List<?> tuples, Version version, boolean isBot, Map<String, Integer> keyMap) {
        var rawValues = new LinkedHashMap<Long, Object>();
        for (var tupleObj : tuples) {
            if (!(tupleObj instanceof List<?> kv) || kv.size() < 2) continue;
            var keyObj = kv.get(0);
            if (!(keyObj instanceof Long key)) continue;
            rawValues.put(key, kv.get(1));
        }
        return fromRawValues(rawValues, version, isBot, keyMap);
    }

    static PlayerStateData fromRawValues(Map<Long, Object> rawValues, Version version, boolean isBot, Map<String, Integer> keyMap) {
        var psd = new PlayerStateData();
        psd.version = version;
        psd.keyMap = keyMap != null ? keyMap : Map.of();

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
