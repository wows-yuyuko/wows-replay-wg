package com.shinoaki.wowsreplay.core.spi;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.spec.EntitySpec;

import java.util.List;

/**
 * 提供实体规范定义（来自游戏 .def 文件）。
 *
 * <p>解耦 wowsunpack 的主要抽象：从游戏数据加载实体规范并按版本缓存。</p>
 */
@FunctionalInterface
public interface EntitySpecProvider {

    /**
     * 加载指定游戏版本的实体规范。
     *
     * @param version the replay's game version
     * @return ordered list of entity specs (index = entity_type - 1)
     */
    List<EntitySpec> loadSpecs(Version version);

    /**
     * 空提供者 — no entity specs. Packet parsing will fail
     * on entity-dependent packets (EntityCreate, EntityProperty, etc.)
     * but spec-independent packets still work.
     */
    static EntitySpecProvider empty() {
        return version -> List.of();
    }
}
