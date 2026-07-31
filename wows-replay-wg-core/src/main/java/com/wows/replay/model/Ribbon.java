package com.wows.replay.model;

/**
 * A ribbon (achievement/ribbon) earned during battle.
 *
 * @param id   the numeric ribbon type ID from the replay
 * @param name human-readable name, or {@code "ribbon_" + id} if unknown
 */
public record Ribbon(int id, String name) {

    /** Create a Ribbon with a fallback name when the ID is unrecognized. */
    public static Ribbon of(int id) {
        return new Ribbon(id, "ribbon_" + id);
    }

    /** Create a Ribbon with an explicit name (e.g. from game constants). */
    public static Ribbon of(int id, String name) {
        return new Ribbon(id, name != null ? name : "ribbon_" + id);
    }
}
