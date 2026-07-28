package com.wows.replay.spec.spi;

import com.wows.replay.spec.types.GameParamId;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 提供游戏参数查找（舰船名称、属性等）。
 *
 * <p>对 wowsunpack GameParams.data 的抽象。's GameParams.data loading.
 * 可从游戏文件或预提取的 JSON dump 加载。</p>
 */
public interface GameParamProvider {

    /**
     * 按 ID 获取游戏参数的 index/name。
     *
     * @param id the ship/equipment parameter ID
     * @return the parameter index string (e.g. "PASB018_Alaska_1950")
     */
    Optional<String> paramNameById(GameParamId id);

    /**
     * All GameParamId → index mappings for ID resolution in reports.
     */
    default Map<Long, String> paramNames() { return Collections.emptyMap(); }

    /** 空提供者 — all lookups return empty. */
    static GameParamProvider empty() {
        return id -> Optional.empty();
    }
}
