package com.wows.replay.spec.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameParamIdTest {

    @Test
    @DisplayName("long constructor")
    void longConstructor() {
        var id = new GameParamId(4181636944L);
        assertEquals(4181636944L, id.value());
    }

    @Test
    @DisplayName("int constructor converts unsigned")
    void intConstructorUnsigned() {
        // buf.getInt() returns -113330352 for wire value 4181636944
        var id = new GameParamId(-113330352);
        assertEquals(4181636944L, id.value());
    }

    @Test
    @DisplayName("small int values passthrough")
    void intConstructorSmall() {
        var id = new GameParamId(42);
        assertEquals(42L, id.value());
    }

    @Test
    @DisplayName("ZERO constant")
    void zero() {
        assertEquals(0L, GameParamId.ZERO.value());
    }

    @Test
    @DisplayName("compareTo")
    void compareTo() {
        assertTrue(new GameParamId(100).compareTo(new GameParamId(50)) > 0);
        assertEquals(0, new GameParamId(42).compareTo(new GameParamId(42)));
    }

    @Test
    @DisplayName("toString")
    void toStringTest() {
        assertEquals("4181636944", new GameParamId(4181636944L).toString());
    }
}
