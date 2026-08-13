package com.shinoaki.wowsreplay.core.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WowsInfo} 解析测试（wowsinfo.json 格式：条目内部名作键，值带 id/icon；skills 带 skillType，取条目内部名）。
 */
class WowsInfoTest {

    private static final String SAMPLE = """
        {
          "ships": {
            "4292851696": { "type": "AirCarrier" }
          },
          "modernizations": {
            "PCM003_Airplanes_Mod_I": { "icon": "PCM003_Airplanes_Mod_I", "id": 4290957232 },
            "noId": { "icon": "IDS_N", "id": 0 }
          },
          "abilities": {
            "PCY001_CrashCrew": { "icon": "PCY001_CrashCrew", "id": 4293042096 }
          },
          "exteriors": {
            "PAEM001_BlackFriday_Sims": { "type": "MSkin", "icon": "PAEM001_BlackFriday_Sims", "id": 4293521392 },
            "noIcon": { "type": "MSkin", "icon": "", "id": 4293521393 }
          },
          "skills": {
            "TorpedoReload": { "skillType": 4 },
            "TorpedoSpeed":  { "skillType": 24 },
            "dup":           { "skillType": 4 }
          },
          "version": "15.6.0.0.12830008"
        }
        """;

    @Test
    void parsesIdNameSections() {
        var w = WowsInfo.fromJson(SAMPLE);

        assertEquals("PCM003_Airplanes_Mod_I", w.modernization(4290957232L).icon());
        var cons = w.consumable(4293042096L);
        assertNotNull(cons);
        assertEquals("PCY001_CrashCrew", cons.icon());
        var ext = w.exterior(4293521392L);
        assertNotNull(ext);
        assertEquals("MSkin", ext.type());
        assertEquals("PAEM001_BlackFriday_Sims", ext.icon());
        assertNull(w.modernization(1L), "未知 id 返回 null");
        assertNull(w.modernization(0L), "id=0 条目跳过");
        assertNull(w.exterior(4293521393L), "空 icon 条目跳过");
    }

    @Test
    void shipTypeParsedByShipId() {
        var w = WowsInfo.fromJson(SAMPLE);
        assertEquals("AirCarrier", w.shipType(4292851696L));
        assertNull(w.shipType(9999999999L), "未知船返回 null");
    }

    @Test
    void skillTypeMapsToSkills() {
        var w = WowsInfo.fromJson(SAMPLE);
        assertEquals("TorpedoSpeed", w.skill(24).icon());
        assertEquals("dup", w.skill(4).icon(), "重复 skillType 取最后一个");
        assertNull(w.skill(999));
    }

    @Test
    void emptyJsonYieldsEmpty() {
        var w = WowsInfo.fromJson("{}");
        assertNull(w.modernization(1L));
        assertNull(w.skill(1));
    }
}
