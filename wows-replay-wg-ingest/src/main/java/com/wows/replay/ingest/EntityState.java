package com.wows.replay.ingest;

import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified entity state — the single representation of an entity across the ingest layer.
 *
 * <p>Unified representation of an entity across the ingest layer. Uses sentinel
 * values for missing data ({@code -1f} for health, {@code NaN} for heading,
 * {@code -1} for teamId, {@code null} for metaId/playerName).</p>
 */
public final class EntityState {

    public final EntityId id;
    public String type;
    /** Kind from the EntityCreate packet (Vehicle/Building/SmokeScreen/InteractiveZone/...). */
    public String kind;
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
    /** 战斗内 meta id（= players 表 key；注意不是账号 ID） */
    public Long metaId;
    public String playerName;
    /** 船长参数 id（EntityCreate 时从 crewModifiersCompactParams.paramsId 解析，之后永不刷新）。 */
    public Long captainParamsId;
    /** Raw ship configuration blob */
    public byte[] shipConfig;
    /** Smoke screen radius (for SmokeScreen entities) */
    public float smokeRadius;

    // ── Minimap 追踪（updateMinimapVisionInfo）──────────────────────
    /** 归一化小地图坐标 x ∈ [-1.5, 2.49] */
    public float minimapX = Float.NaN;
    public float minimapZ = Float.NaN;
    /** 小地图 heading（度）；Position 包的 heading 是弧度，单独存放避免覆盖 */
    public float minimapHeading = Float.NaN;
    public boolean visible = true;
    public int visibilityFlags;
    public float lastUpdated;

    // ── Extended state (Phase 4 ingest fills these) ──────────────────

    /** Gun turret states: gunId → {yaw, pitch} */
    public final Map<Integer, float[]> turrets = new LinkedHashMap<>();
    /** Ammo by weapon type → GameParamId */
    public final Map<Integer, Long> ammoByWeapon = new LinkedHashMap<>();
    /** Number of shots fired */
    public long shotsFired;

    // ── Vehicle.state 嵌套更新（NestedPropertyUpdate 0x23 应用）──────────
    /** 主炮能量（state.battery.energy，FloatVal ~300） */
    public float batteryEnergy;
    /** 反潜弹幕目标实体 id 列表（state.atba.atbaTargets[N]） */
    public List<Long> atbaTargets;
    /** 命中弹痕数量（state.decals.shotDecals，外观数据仅计数） */
    public int shotDecals;

    public EntityState(EntityId id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "EntityState[id=" + id + ", type=" + type + ", hp=" + health + "/" + maxHealth
            + ", team=" + teamId + ", alive=" + isAlive + ", pos=(" + x + "," + y + "," + z + ")]";
    }
}
