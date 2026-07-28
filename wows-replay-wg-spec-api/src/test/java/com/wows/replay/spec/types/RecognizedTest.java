package com.wows.replay.spec.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecognizedTest {

    @Test
    @DisplayName("Known 变体")
    void known() {
        var r = WeaponType.fromRaw(0);
        assertInstanceOf(Recognized.Known.class, r);
        assertEquals(WeaponType.ARTILLERY, ((Recognized.Known<WeaponType>) r).value());
    }

    @Test
    @DisplayName("Unknown 变体")
    void unknown() {
        var r = WeaponType.fromRaw(99);
        assertInstanceOf(Recognized.Unknown.class, r);
        assertEquals(99, ((Recognized.Unknown<?>) r).raw());
    }

    @Test
    @DisplayName("fromRaw with reverse map")
    void fromRawWithMap() {
        var map = Map.of(1, TestEnum.A, 2, TestEnum.B);
        var r = Recognized.fromRaw(1, map);
        assertInstanceOf(Recognized.Known.class, r);
    }

    @Test
    @DisplayName("fromRaw with unknown id")
    void fromRawUnknown() {
        var map = Map.of(1, TestEnum.A);
        var r = Recognized.fromRaw(99, map);
        assertInstanceOf(Recognized.Unknown.class, r);
    }

    @Test
    @DisplayName("intoKnown returns value for Known")
    void intoKnownPresent() {
        var r = WeaponType.fromRaw(0);
        assertTrue(r.intoKnown().isPresent());
    }

    @Test
    @DisplayName("intoKnown returns empty for Unknown")
    void intoKnownEmpty() {
        var r = WeaponType.fromRaw(99);
        assertTrue(r.intoKnown().isEmpty());
    }

    @Test
    @DisplayName("WeaponLockType fromRaw")
    void weaponLockType() {
        assertEquals(WeaponLockType.TARGET, ((Recognized.Known<WeaponLockType>) WeaponLockType.fromRaw(3)).value());
        assertInstanceOf(Recognized.Unknown.class, WeaponLockType.fromRaw(99));
    }

    @Test
    @DisplayName("unknown(int) factory")
    void unknownFactory() {
        var r = Recognized.unknown(42);
        assertInstanceOf(Recognized.Unknown.class, r);
        assertEquals(42, ((Recognized.Unknown<?>) r).raw());
    }

    enum TestEnum { A, B }
}
