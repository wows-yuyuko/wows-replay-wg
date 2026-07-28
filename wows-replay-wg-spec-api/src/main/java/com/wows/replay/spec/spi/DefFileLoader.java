package com.wows.replay.spec.spi;

import java.io.IOException;

/**
 * Abstraction over game data file access.
 *
 * <p>Implementations may load from the file system, a virtual file system
 * (VFS), an in-memory map (for testing), or a network source.
 * Mirrors Rust's {@code DataFileLoader} trait.</p>
 */
@FunctionalInterface
public interface DefFileLoader {

    /**
     * Read the contents of a game data file.
     *
     * @param path file path within the game data tree
     *             (e.g. {@code "scripts/entity_defs/Avatar.def"})
     * @return file contents as raw bytes
     * @throws IOException if the file cannot be read
     */
    byte[] get(String path) throws IOException;
}
