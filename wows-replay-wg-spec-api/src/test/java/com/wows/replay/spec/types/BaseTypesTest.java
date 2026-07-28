package com.wows.replay.spec.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseTypesTest {

    @Test @DisplayName("Vec2 ZERO")
    void vec2Zero() {
        assertEquals(0f, Vec2.ZERO.x());
        assertEquals(0f, Vec2.ZERO.y());
    }

    @Test @DisplayName("Vec3 ZERO")
    void vec3Zero() {
        assertEquals(0f, Vec3.ZERO.x());
        assertEquals(0f, Vec3.ZERO.y());
        assertEquals(0f, Vec3.ZERO.z());
    }

    @Test @DisplayName("Rot3 ZERO")
    void rot3Zero() {
        assertEquals(0f, Rot3.ZERO.yaw());
        assertEquals(0f, Rot3.ZERO.pitch());
        assertEquals(0f, Rot3.ZERO.roll());
    }

    @Test @DisplayName("EntityId compareTo 比较")
    void entityIdCompare() {
        assertTrue(new EntityId(10).compareTo 比较(new EntityId(5)) > 0);
        assertEquals(0, new EntityId(42).compareTo 比较(new EntityId(42)));
    }

    @Test @DisplayName("GameClock compareTo 比较")
    void gameClockCompare() {
        assertTrue(new GameClock(10f).compareTo 比较(new GameClock(5f)) > 0);
        assertEquals(0, new GameClock(3.5f).compareTo 比较(new GameClock(3.5f)));
        assertEquals(0f, GameClock.ZERO.seconds());
    }

    @Test @DisplayName("AccountId with int values")
    void accountId() {
        var id = new AccountId(123456);
        assertEquals(123456, id.value());
    }

    @Test @DisplayName("ArenaId with long values")
    void arenaId() {
        var id = new ArenaId(1234567890123L);
        assertEquals(1234567890123L, id.value());
    }

    @Test @DisplayName("TeamId constants")
    void teamId() {
        assertEquals(1, TeamId.TEAM_1.value());
        assertEquals(2, TeamId.TEAM_2.value());
    }
}
