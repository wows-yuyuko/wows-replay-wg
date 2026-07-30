package com.wows.replay.gamedata;

import com.wows.replay.core.entity.EntitySpec;
import com.wows.replay.core.spi.DefFileLoader;
import com.wows.replay.core.spi.EntitySpecProvider;
import com.wows.replay.core.types.Version;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link EntitySpecProvider} that loads entity specs from game
 * .def files using an XML parser.
 *
 * <p>Specs are cached by version for repeated use.  Requires a
 * {@link DefFileLoader} to access game data files.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DefFileLoader loader = path -> Files.readAllBytes(gameDir.resolve(path));
 * var provider = new XmlEntitySpecProvider(loader);
 * List<EntitySpec> specs = provider.loadSpecs(version);
 * }</pre>
 */
public final class XmlEntitySpecProvider implements EntitySpecProvider {

    private final XmlDefParser parser;
    private final Map<Version, List<EntitySpec>> cache = new ConcurrentHashMap<>();

    public XmlEntitySpecProvider(DefFileLoader loader) {
        this.parser = new XmlDefParser(loader);
    }

    @Override
    public List<EntitySpec> loadSpecs(Version version) {
        return cache.computeIfAbsent(version, v -> {
            try {
                return parser.parseAll(v);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load entity specs for " + v, e);
            }
        });
    }
}
