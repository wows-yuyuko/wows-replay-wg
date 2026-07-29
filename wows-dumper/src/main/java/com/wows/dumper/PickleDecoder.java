package com.wows.dumper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 最小 Python pickle 协议解码器，仅支持 BigWorld 回放所需的类型。
 *
 * <p>支持的 opcode:
 * <ul>
 * <li>PROTO (\x80) — 协议头</li>
 * <li>EMPTY_LIST (]) / EMPTY_TUPLE ()) </li>
 * <li>MARK (() / TUPLE (t) / TUPLE1-TUPLE3</li>
 * <li>BININT1 (K) / BININT2 (M) / BININT (J) / INT (I)</li>
 * <li>SHORT_BINSTRING (U) / STRING (S)</li>
 * <li>BINFLOAT (G) / FLOAT (F)</li>
 * <li>NONE (N)</li>
 * <li>APPEND (a) / APPENDS (e)</li>
 * <li>BINPUT (q) / BINGET (h)</li>
 * <li>STOP (.)</li>
 * </ul>
 */
final class PickleDecoder {

    private final byte[] data;
    private int pos;
    private final List<Object> stack = new ArrayList<>();
    private final List<Integer> marks = new ArrayList<>();
    private final Map<Integer, Object> memo = new HashMap<>();

    private PickleDecoder(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    /** 解码 pickle 字节，返回顶层值。 */
    static Object decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        var d = new PickleDecoder(data);
        return d.parse();
    }

    private Object parse() {
        while (pos < data.length) {
            int op = data[pos++] & 0xFF;
            switch (op) {
                case 0x80: // PROTO
                    pos++; // skip proto version byte
                    break;
                case '(': // MARK
                    marks.add(stack.size());
                    break;
                case '.': // STOP
                    return stack.isEmpty() ? null : stack.getLast();
                case '0': // POP
                    if (!stack.isEmpty()) stack.removeLast();
                    break;
                case ']': // EMPTY_LIST
                    stack.add(new ArrayList<>());
                    break;
                case ')': // EMPTY_TUPLE
                    stack.add(List.of());
                    break;
                case 'a': { // APPEND
                    var val = stack.removeLast();
                    @SuppressWarnings("unchecked")
                    var list = (List<Object>) stack.getLast();
                    list.add(val);
                    break;
                }
                case 'e': { // APPENDS
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    var slice = new ArrayList<>(stack.subList(mark, stack.size()));
                    stack.subList(mark, stack.size()).clear();
                    @SuppressWarnings("unchecked")
                    var list = (List<Object>) stack.getLast();
                    list.addAll(slice);
                    break;
                }
                case 't': { // TUPLE
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    var tuple = List.copyOf(stack.subList(mark, stack.size()));
                    stack.subList(mark, stack.size()).clear();
                    stack.add(tuple);
                    break;
                }
                case 0x85: // TUPLE1
                    stack.add(List.of(stack.removeLast()));
                    break;
                case 0x86: { // TUPLE2
                    var v2 = stack.removeLast();
                    var v1 = stack.removeLast();
                    stack.add(List.of(v1, v2));
                    break;
                }
                case 0x87: { // TUPLE3
                    var v3 = stack.removeLast();
                    var v2 = stack.removeLast();
                    var v1 = stack.removeLast();
                    stack.add(List.of(v1, v2, v3));
                    break;
                }
                case 'K': // BININT1 (1 byte unsigned)
                    stack.add((long) (data[pos++] & 0xFF));
                    break;
                case 'M': // BININT2 (2 bytes little-endian unsigned)
                    stack.add((long) ((data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)));
                    break;
                case 'J': { // BININT (4 bytes little-endian signed)
                    int v = (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)
                          | ((data[pos++] & 0xFF) << 16) | (data[pos++] << 24);
                    stack.add((long) v);
                    break;
                }
                case 'I': { // INT (newline-terminated string repr)
                    var sb = new StringBuilder();
                    while (pos < data.length && data[pos] != '\n') sb.append((char) data[pos++]);
                    pos++; // skip \n
                    stack.add(Long.parseLong(sb.toString()));
                    break;
                }
                case 'G': { // BINFLOAT (8 bytes little-endian double)
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits |= (long) (data[pos++] & 0xFF) << (i * 8);
                    stack.add(Double.longBitsToDouble(bits));
                    break;
                }
                case 'F': { // FLOAT (newline-terminated string repr)
                    var sb = new StringBuilder();
                    while (pos < data.length && data[pos] != '\n') sb.append((char) data[pos++]);
                    pos++; // skip \n
                    stack.add(Double.parseDouble(sb.toString()));
                    break;
                }
                case 'U': { // SHORT_BINSTRING
                    int len = data[pos++] & 0xFF;
                    var s = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    stack.add(s);
                    break;
                }
                case 'T': { // BINUNICODE
                    int len = readInt32();
                    var s = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    stack.add(s);
                    break;
                }
                case 'S': { // STRING (quoted string)
                    char quote = (char) data[pos++];
                    var sb = new StringBuilder();
                    while (pos < data.length) {
                        byte b = data[pos++];
                        if (b == '\\') { sb.append((char) data[pos++]); }
                        else if (b == quote) break;
                        else sb.append((char) b);
                    }
                    pos++; // skip \n
                    stack.add(sb.toString());
                    break;
                }
                case 'N': // NONE
                    stack.add(null);
                    break;
                case 'q': { // BINPUT
                    int idx = data[pos++] & 0xFF;
                    memo.put(idx, stack.getLast());
                    break;
                }
                case 'h': { // BINGET
                    int idx = data[pos++] & 0xFF;
                    stack.add(memo.get(idx));
                    break;
                }
                case 'r': { // SETITEM
                    var val = stack.removeLast();
                    var key = stack.removeLast();
                    @SuppressWarnings("unchecked")
                    var dict = (Map<Object, Object>) stack.getLast();
                    dict.put(key, val);
                    break;
                }
                case 'u': { // SETITEMS
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    @SuppressWarnings("unchecked")
                    var dict = (Map<Object, Object>) stack.get(stack.size() - mark - 1);
                    for (int i = mark; i < stack.size(); i += 2) {
                        dict.put(stack.get(i), stack.get(i + 1));
                    }
                    stack.subList(mark, stack.size()).clear();
                    break;
                }
                case '{': // EMPTY_DICT
                    stack.add(new LinkedHashMap<>());
                    break;
                default:
                    // Unknown opcode — skip and hope
            }
        }
        return stack.isEmpty() ? null : stack.getLast();
    }

    private int readInt32() {
        return (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)
             | ((data[pos++] & 0xFF) << 16) | (data[pos++] << 24);
    }

    // ── 高层 API：解析 ArenaState 玩家列表 ──────────────────────────────

    /**
     * 从 onArenaStateReceived 的 args[3] BLOB 中解析玩家状态。
     * 返回 (entity_id → db_id) 和 (db_id → username) 映射。
     */
    record ArenaPlayers(
        Map<Integer, Long> entityToDbId,   // entity_id → db_id
        Map<Long, String> dbIdToName,      // db_id → username
        Map<Long, Integer> dbIdToTeam,     // db_id → team_id
        Map<Long, Long> dbIdToMetaShipId   // db_id → meta_ship_id (用于匹配)
    ) {}

    /** 解析 onArenaStateReceived 中的玩家列表 pickle BLOB。 */
    static ArenaPlayers parseArenaPlayers(byte[] pickledBlob) {
        var entityToDbId = new LinkedHashMap<Integer, Long>();
        var dbIdToName = new LinkedHashMap<Long, String>();
        var dbIdToTeam = new LinkedHashMap<Long, Integer>();
        var dbIdToMetaShipId = new LinkedHashMap<Long, Long>();

        Object root = decode(pickledBlob);
        if (!(root instanceof List<?> players)) return new ArenaPlayers(entityToDbId, dbIdToName, dbIdToTeam, dbIdToMetaShipId);

        for (Object playerObj : players) {
            // 每个 player 是 List of tuples: [(key0, val0), (key1, val1), ...]
            if (!(playerObj instanceof List<?> tuples)) continue;

            long dbId = 0, metaShipId = 0;
            int entityId = 0, teamId = -1;
            String name = "";

            for (Object tupleObj : tuples) {
                if (!(tupleObj instanceof List<?> kv) || kv.size() < 2) continue;
                Object keyObj = kv.get(0);
                if (!(keyObj instanceof Long key)) continue;
                Object val = kv.get(1);

                switch ((int) (long) key) {
                    case 0:  // accountDBID
                        dbId = longVal(val);
                        break;
                    case 11: // id (meta_ship_id, for matching with meta.vehicles)
                        metaShipId = longVal(val);
                        break;
                    case 25: // name
                        name = val instanceof String s ? s : "";
                        break;
                    case 33: // shipId (= entity_id)
                        entityId = (int) longVal(val);
                        break;
                    case 36: // teamId
                        teamId = (int) longVal(val);
                        break;
                }
            }

            if (entityId > 0 && dbId > 0) {
                entityToDbId.put(entityId, dbId);
                dbIdToName.put(dbId, name);
                dbIdToTeam.put(dbId, teamId);
                dbIdToMetaShipId.put(dbId, metaShipId);
            }
        }

        return new ArenaPlayers(entityToDbId, dbIdToName, dbIdToTeam, dbIdToMetaShipId);
    }

    private static long longVal(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double) return ((Double) v).longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
