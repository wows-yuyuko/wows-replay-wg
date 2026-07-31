package com.wows.replay.ingest;

import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;

import java.util.*;

/**
 * Unified entity state — the single representation of an entity across the ingest layer.
 *
 * <p>Merges the previous {@code EntityState} (from RichExtractor) and
 * {@code EntityComponents} (BattleWorld inner class) into one class.
 * Uses sentinel values for missing data ({@code -1f} for health, {@code NaN} for heading,
 * {@code -1} for teamId, {@code null} for dbId/playerName).</p>
 */
public final class EntityState {

    public final EntityId id;
    public String type;
    public float health = -1f;
    public float maxHealth = -1f;
    public boolean isAlive = true;
    public boolean isInvisible;
    public boolean isBot;
    public float x, y, z;
    public float heading = Float.NaN;
    public int teamId = -1;
    /** 0=self, 1=ally, 2=enemy */
    public int relation = -1;
    public GameParamId vehicleId;
    /** Vehicle → Avatar owner entity id */
    public EntityId ownerEntityId;
    public Long dbId;
    public String playerName;
    /** Raw ship configuration blob */
    public byte[] shipConfig;
    /** Smoke screen radius (for SmokeScreen entities) */
    public float smokeRadius;

    // ── Extended state (Phase 4 ingest fills these) ──────────────────

    /** Gun turret states: gunId → {yaw, pitch} */
    public final Map<Integer, float[]> turrets = new LinkedHashMap<>();
    /** Ammo by weapon type → GameParamId */
    public final Map<Integer, Long> ammoByWeapon = new LinkedHashMap<>();
    /** Ribbons earned by this entity */
    public final List<Integer> ribbons = new ArrayList<>();
    /** Cumulative damage stats */
    public final List<DamageStatEntry> damageStats = new ArrayList<>();
    /** Number of shots fired */
    public long shotsFired;

    public EntityState(EntityId id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "EntityState[id=" + id + ", type=" + type + ", hp=" + health + "/" + maxHealth
            + ", team=" + teamId + ", alive=" + isAlive + ", pos=(" + x + "," + y + "," + z + ")]";
    }

    // ── Inner types ──────────────────────────────────────────────────

    public record DamageStatEntry(long weaponId, long categoryId, long count, double total) {}
}
