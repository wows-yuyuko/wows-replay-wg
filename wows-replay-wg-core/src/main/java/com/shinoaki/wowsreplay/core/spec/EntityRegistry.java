package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.spi.DefFileLoader;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;

import java.io.IOException;
import java.util.List;

/**
 * Default {@link EntitySpecProvider} that loads entity specs from game
 * .def files using an XML parser.
 *
 * <p>纯解析器，自身<b>不缓存</b>：解析结果的缓存统一由 {@link GameDataCache} 负责
 * （按 major.minor.patch 键缓存 {@code List<EntitySpec>}，并复用本实例）。
 * {@code version} 参数不影响结果——{@link DefFileLoader} 已绑定单一游戏数据目录，
 * {@link SpecLoader} 解析时忽略版本。</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DefFileLoader loader = path -> Files.readAllBytes(gameDir.resolve(path));
 * var provider = new EntityRegistry(loader);
 * List<EntitySpec> specs = provider.loadSpecs(version);
 * }</pre>
 */
public final class EntityRegistry implements EntitySpecProvider {

    private final SpecLoader parser;

    public EntityRegistry(DefFileLoader loader) {
        this.parser = new SpecLoader(loader);
    }

    @Override
    public List<EntitySpec> loadSpecs(Version version) {
        try {
            return parser.parseAll(version);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load entity specs for " + version, e);
        }
    }
}
