package com.wows.replay.analyzer;

/**
 * Aggregated entity state — replaces scattered per-attribute HashMaps.
 *
 * <p>Every entity encountered in the replay stream (Avatar, Vehicle, Shell, etc.)
 * has its properties collected here. Fields use -1 / 0 / false / null as "unknown"
 * sentinels.</p>
 */
public final class EntityState {

    public int id;
    public String type;

    // ── health ──────────────────────────────────────────────────────────
    public float health    = -1f;
    public float maxHealth = -1f;
    public boolean isAlive = true;
    public boolean isInvisible;

    // ── position ────────────────────────────────────────────────────────
    public float x, y, z;
    public float heading = Float.NaN;   // yaw

    // ── team ────────────────────────────────────────────────────────────
    public int teamId = -1;

    // ── links ───────────────────────────────────────────────────────────
    public long vehicleId;              // GameParamId for Vehicle entities
    public int ownerEntityId;           // Vehicle → Avatar

    // ── player (Avatar only) ────────────────────────────────────────────
    public Long dbId;
    public String playerName;

    public EntityState(int id, String type) {
        this.id = id;
        this.type = type;
    }

    @Override
    public String toString() {
        return "EntityState[" + id + " " + type + " team=" + teamId
            + " hp=" + health + "/" + maxHealth + " alive=" + isAlive
            + " pos=(" + x + "," + y + "," + z + ")"
            + (dbId != null ? " player=" + playerName + "(" + dbId + ")" : "")
            + "]";
    }
}
