package com.shinoaki.wowsreplay.core.pickle;

import com.shinoaki.wowsreplay.core.decode.PlayerStateData;
import com.shinoaki.wowsreplay.core.model.Version;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 中文/非 ASCII 用户名处理：arena 名册 pickle 的 name 字段是 BINSTRING 字节串（UTF-8 内容），
 * 经 ISO-8859-1 保真解码后必须还原，否则输出乱码。
 */
class PickleReaderTest {

    private static final String CN_NAME = "笨笨奔奔玩的开心";

    @Test
    void decodeTextRepairsMojibake() {
        // UTF-8 字节按 ISO-8859-1 解码得到的乱码 → 应还原为原文
        String mojibake = new String(CN_NAME.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertEquals(CN_NAME, PickleReader.decodeText(mojibake));
    }

    @Test
    void decodeTextLeavesProperUnicodeAlone() {
        // 已是正确 Unicode（BINUNICODE 解码结果），不得改动
        assertEquals(CN_NAME, PickleReader.decodeText(CN_NAME));
        assertEquals("café", PickleReader.decodeText("café"));
        assertEquals("José_ASIA", PickleReader.decodeText("José_ASIA"));
        assertEquals("lord_of_the_squids", PickleReader.decodeText("lord_of_the_squids"));
        assertNull(PickleReader.decodeText(null));
        assertEquals("", PickleReader.decodeText(""));
    }

    @Test
    void decodeTextLeavesGenuineLatin1Alone() {
        // 真实 Latin-1 文本（字节不是合法 UTF-8），不得改动
        String latin1 = "José"; // é 的 Latin-1 字节 0xE9 不是合法 UTF-8
        assertEquals(latin1, PickleReader.decodeText(latin1));
    }

    @Test
    void parseArenaPlayersChineseName() throws Exception {
        byte[] blob = syntheticArenaPlayers(12345L, 6789L, CN_NAME, 100, 1);
        var arena = PickleReader.parseArenaPlayers(blob);
        assertEquals(CN_NAME, arena.dbIdToName().get(12345L));
        assertEquals(1, arena.entityToDbId().size());
        assertEquals(12345L, (long) arena.entityToDbId().get(100));
    }

    @Test
    void playerStateDataChineseName() {
        // 15.x 字段布局：name=25、clanTag=6、realm=30（与 constants.json NUM_MEMBER_MAP 一致）
        var keyMap = new LinkedHashMap<String, Integer>();
        keyMap.put("accountDBID", 0);
        keyMap.put("clanTag", 6);
        keyMap.put("id", 11);
        keyMap.put("name", 25);
        keyMap.put("realm", 30);
        keyMap.put("shipId", 33);
        keyMap.put("teamId", 36);

        // 模拟 pickle 解码后的原始值：name 是 BINSTRING 的 Latin-1 乱码
        String mojibake = new String(CN_NAME.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        var tuples = new java.util.ArrayList<Object>();
        tuples.add(java.util.List.of(0L, 12345L));
        tuples.add(java.util.List.of(6L, new String("CLAN".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)));
        tuples.add(java.util.List.of(11L, 6789L));
        tuples.add(java.util.List.of(25L, mojibake));
        tuples.add(java.util.List.of(30L, new String("ASIA".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)));
        tuples.add(java.util.List.of(33L, 100L));
        tuples.add(java.util.List.of(36L, 1L));

        var psd = PlayerStateData.fromTuples(tuples, new Version(15, 6, 0, 0), false, keyMap);
        assertEquals(CN_NAME, psd.username());
        assertEquals("CLAN", psd.clan());
        assertEquals("ASIA", psd.realm());
        // raw_with_names 里也应输出还原后的文本
        assertEquals(CN_NAME, psd.rawWithNames().get("name"));
    }

    /** 构造 protocol-2 pickle：List<player>，player = List of (key,value) tuples。 */
    private static byte[] syntheticArenaPlayers(long dbId, long metaShipId, String name, int entityId, int teamId) throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream();
        out.write(0x80); out.write(0x02);          // PROTO 2
        out.write(']');                            // players list
        out.write('(');                            // MARK(player)
        out.write(']');                            // player list
        kv(out, 0, dbId);                          // accountDBID
        kv(out, 11, metaShipId);                   // id (meta_ship_id)
        out.write('('); out.write('K'); out.write(25); // key 25
        out.write('U'); out.write(nameBytes.length);   // SHORT_BINSTRING
        out.write(nameBytes);
        out.write('t'); out.write('a');
        kv(out, 33, entityId);                     // shipId
        kv(out, 36, teamId);                       // teamId
        out.write('a');                            // append player
        out.write('.');                            // STOP
        return out.toByteArray();
    }

    /** 单个 (key, value) tuple：'(' K key <int> 't' 'a'。 */
    private static void kv(ByteArrayOutputStream out, int key, long value) {
        out.write('('); out.write('K'); out.write(key);
        if (value <= 0xFF) {
            out.write('K'); out.write((int) value);
        } else {
            out.write('M'); out.write((int) (value & 0xFF)); out.write((int) ((value >> 8) & 0xFF));
        }
        out.write('t'); out.write('a');
    }
}
