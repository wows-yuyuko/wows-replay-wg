package com.wows.replay.spi;

import com.wows.replay.model.Version;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供游戏常量查找（消耗品、死亡原因、战斗阶段、相机模式等）。
 *
 * <p>常量从游戏 Scripts/ 目录提取，或通过 JSON dump 提供。
 * 无可用 provider 时回退到默认空实现。</p>
 */
public interface GameConstantsProvider {

    /** Human-readable name for a consumable ID, or empty if unknown. */
    default Optional<String> consumableName(int id) { return Optional.empty(); }

    /** Human-readable name for a death reason ID, or empty if unknown. */
    default Optional<String> deathReasonName(int id) { return Optional.empty(); }

    /** Human-readable name for a game mode ID, or empty if unknown. */
    default Optional<String> gameModeName(int id) { return Optional.empty(); }

    /** Human-readable name for a camera mode ID, or empty if unknown. */
    default Optional<String> cameraModeName(int id) { return Optional.empty(); }

    /** Human-readable name for a battle stage ID, or empty if unknown. */
    default Optional<String> battleStageName(int id, Version version) { return Optional.empty(); }

    /**
     * Human-readable name for a FINISH_TYPE ID (battle.xml), or empty if unknown.
     * 外部数据返回的是内部枚举标识（如 "BASE"），与显示名可能不一致——供统一管理器
     * {@code GameConstants} 对未知 id 兜底。
     */
    default Optional<String> finishTypeName(int id, Version version) { return Optional.empty(); }

    /** All known FINISH_TYPE id → name mappings. */
    default Map<Integer, String> finishTypeNames(Version version) { return Collections.emptyMap(); }

    /** Human-readable name for a DAMAGE_STATS category ID, or empty if unknown. */
    default Optional<String> damageStatCategoryName(int id, Version version) { return Optional.empty(); }

    /** All known DAMAGE_STATS category id → name mappings. */
    default Map<Integer, String> damageStatCategories(Version version) { return Collections.emptyMap(); }

    /** All known consumable ID → name mappings. */
    default Map<Integer, String> consumableIds() { return Collections.emptyMap(); }

    /** All known battle stage ID → name mappings. */
    default Map<Integer, String> battleStages(Version version) { return Collections.emptyMap(); }

    /** List of known ribbon names (indexed by ribbon type ID). */
    default List<String> ribbonNames() { return Collections.emptyList(); }

    /**
     * 空提供者——所有查找返回空。
     */
    static GameConstantsProvider empty() {
        return new GameConstantsProvider() {};
    }
}
