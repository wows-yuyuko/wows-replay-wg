package com.shinoaki.wowsreplay.core.model;

/**
 * Cause of a ship's destruction in battle.
 *
 * @param code raw death reason code from the replay
 * @param name human-readable name (resolved via game constants), or {@code "cause_" + code} if unknown
 */
public record DeathCause(int code, String name) {

    /** Create a DeathCause with a fallback name when the code is unrecognized. */
    public static DeathCause of(int code) {
        return new DeathCause(code, "cause_" + code);
    }

    /** Create a DeathCause with an explicit name (e.g. from game constants). */
    public static DeathCause of(int code, String name) {
        return new DeathCause(code, name != null ? name : "cause_" + code);
    }
}
