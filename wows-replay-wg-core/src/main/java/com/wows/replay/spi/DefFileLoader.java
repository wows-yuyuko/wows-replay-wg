package com.wows.replay.spi;

import java.io.IOException;

/**
 * 游戏数据文件访问抽象。
 *
 * <p>Implementations may load from the file system, a virtual file system
 * (VFS), an in-memory map (for testing), or a network source.
 * 对标 Rust {@code DataFileLoader} trait.</p>
 */
@FunctionalInterface
public interface DefFileLoader {

    /**
     * 读取游戏数据文件内容。
     *
     * @param path file path within the game data tree
     *             (e.g. {@code "scripts/entity_defs/Avatar.def"})
     * @return file contents as raw bytes
     * @throws IOException if the file cannot be read
     */
    byte[] get(String path) throws IOException;
}
