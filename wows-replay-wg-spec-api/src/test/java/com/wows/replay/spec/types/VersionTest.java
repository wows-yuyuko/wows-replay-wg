package com.wows.replay.spec.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionTest {

    @Test
    @DisplayName("fromClientExe 解析逗号分隔版本")
    void fromClientExe() {
        var v = Version.fromClientExe("15,6,0,12830008");
        assertEquals(15, v.major());
        assertEquals(6, v.minor());
        assertEquals(0, v.patch());
        assertEquals(12830008, v.build());
    }

    @Test
    @DisplayName("fromClientExe with extra spaces")
    void fromClientExeSpaces() {
        var v = Version.fromClientExe(" 15 , 6 , 0 , 12830008 ");
        assertEquals(15, v.major());
        assertEquals(6, v.minor());
    }

    @Test
    @DisplayName("fromClientExe with null → 0,0,0,0")
    void fromClientExeNull() {
        var v = Version.fromClientExe(null);
        assertEquals(0, v.major());
        assertEquals(0, v.build());
    }

    @Test
    @DisplayName("fromAccountDef 现代格式")
    void fromAccountDefModern() {
        var xml = "<root><Properties><curVersion_15_1_0_11965230></curVersion_15_1_0_11965230></Properties></root>";
        var v = Version.fromAccountDef(xml);
        assertEquals(15, v.major());
        assertEquals(1, v.minor());
        assertEquals(0, v.patch());
        assertEquals(11965230, v.build());
    }

    @Test
    @DisplayName("fromAccountDef with release_ prefix")
    void fromAccountDefRelease() {
        var xml = "<root><Properties><curVersion_release_11_4_0_5624555></curVersion_release_11_4_0_5624555></Properties></root>";
        var v = Version.fromAccountDef(xml);
        assertEquals(11, v.major());
        assertEquals(4, v.minor());
        assertEquals(0, v.patch());
        assertEquals(5624555, v.build());
    }

    @Test
    @DisplayName("fromAccountDef legacy 5-part")
    void fromAccountDefLegacy() {
        var xml = "<root><Properties><curVersion_Release_0_6_13_0_296659></curVersion_Release_0_6_13_0_296659></Properties></root>";
        var v = Version.fromAccountDef(xml);
        assertEquals(0, v.major());
        assertEquals(6, v.minor());
        assertEquals(13, v.patch());
        assertEquals(296659, v.build());
    }

    @Test
    @DisplayName("fromAccountDef missing → 0,0,0,0")
    void fromAccountDefMissing() {
        var v = Version.fromAccountDef("<root></root>");
        assertEquals(0, v.major());
    }

    @Test
    @DisplayName("isAtLeast 比较")
    void isAtLeast 比较() {
        var older = Version.fromClientExe("0,10,9,0");
        var newer = Version.fromClientExe("0,10,10,0");
        assertTrue(newer.isAtLeast 比较(older));
        assertTrue(newer.isAtLeast 比较(newer));
        assertFalse(older.isAtLeast 比较(newer));
    }

    @Test
    @DisplayName("isAtLeast 比较 different minor")
    void isAtLeast 比较Minor() {
        assertTrue(Version.fromClientExe("0,11,0,0").isAtLeast 比较(Version.fromClientExe("0,10,9,0")));
    }

    @Test
    @DisplayName("toPath 路径")
    void toPath 路径() {
        assertEquals("15.6.0", Version.fromClientExe("15,6,0,12830008").toPath 路径());
    }
}
