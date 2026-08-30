package com.shinoaki.wowsreplay.core;

import com.shinoaki.wowsreplay.core.crypto.BlowfishCbcDecryptors;
import com.shinoaki.wowsreplay.core.model.GameClock;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.packet.RawPacket;
import com.shinoaki.wowsreplay.core.packet.RawPacketIterator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 已解析的 WoWs 回放文件。
 *
 * <h3>File format</h3>
 * <pre>
 * [magic: u32]              — always 0x12345678
 * [block_count: u32]        — including the metadata block
 * [meta_len: u32]           — length of metadata JSON
 * [meta: u8 × meta_len]     — UTF-8 JSON metadata
 * [extra_blocks: ...]       — additional data blocks (optional)
 * [decompressed_size: u32]  — expected size after zlib decompress
 * [compressed_size: u32]    — size of the following encrypted+zlib'd data
 * [encrypted_packets: ...]  — Blowfish-CBC encrypted, zlib-compressed packet stream
 * </pre>
 *
 * <p>Decryption uses Blowfish-CBC with a hardcoded 16-byte key and all-zero IV.
 * The decrypted data is then inflated with zlib to produce the raw packet stream.</p>
 */
public final class ReplayFile {

    /** File magic number (always 0x12345678). Not checked strictly. */
    private static final int MAGIC = 0x12345678;

    private final ReplayMeta meta;
    private final String rawMeta;
    private final byte[] packetData;
    private final Version version;
    /** 游戏数据基础目录（含 data-M.m.p.b/live 子目录），可为 null。 */
    private final Path gameDataBase;

    private ReplayFile(ReplayMeta meta, String rawMeta, byte[] packetData, Path gameDataBase) {
        this.meta = meta;
        this.rawMeta = rawMeta;
        this.packetData = packetData;
        this.version = Version.fromClientExe(meta.clientVersionFromExe());
        this.gameDataBase = gameDataBase;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** The parsed replay metadata. */
    public ReplayMeta meta() {
        return meta;
    }

    /** Raw metadata JSON string. */
    public String rawMeta() {
        return rawMeta;
    }

    /** Decrypted, decompressed packet stream bytes. */
    public byte[] packetData() {
        return packetData.clone();
    }

    /** Game version parsed from metadata. */
    public Version version() {
        return version;
    }

    /** 游戏数据基础目录（{@link #fromFile(Path, Path)} 时指定），无则为 null。 */
    public Path gameDataBase() {
        return gameDataBase;
    }

    /** Total number of packets in the stream. */
    public int packetCount() {
        var iter = packetIterator();
        int count = 0;
        while (iter.hasNext()) {
            iter.next();
            count++;
        }
        return count;
    }

    /**
     * Create a raw packet iterator that walks the decrypted packet stream.
     * Uses the replay's game version for packet-ID layout selection.
     */
    public RawPacketIterator packetIterator() {
        return new RawPacketIterator(packetData, version);
    }

    /**
     * Stream all raw packets (lazy). Convenience wrapper around {@link #packetIterator()}.
     */
    public Stream<RawPacket> packets() {
        var iter = packetIterator();
        return Stream.generate(() -> iter.hasNext() ? iter.next() : null)
                .takeWhile(Objects::nonNull);
    }

    /**
     * Get the battle start clock (first non-zero clock in the stream).
     * Returns {@link GameClock#ZERO} if all packets have clock=0.
     */
    public GameClock battleStartClock() {
        var iter = packetIterator();
        while (iter.hasNext()) {
            var pkt = iter.next();
            if (pkt.clock().seconds() > 0f) return pkt.clock();
        }
        return GameClock.ZERO;
    }

    @Override
    public String toString() {
        return "ReplayFile[player=" + meta.playerName() + ", map=" + meta.mapName()
               + ", version=" + version + ", packets=" + packetCount() + "]";
    }

    // ── Parsing (byte[] → ReplayFile) ──────────────────────────────────────

    /**
     * Parse a replay from an in-memory byte array.
     *
     * @param bytes raw .wowsreplay file bytes
     * @return parsed ReplayFile with decrypted, decompressed packet data
     * @throws ReplayException on parse/decompress/decrypt errors
     */
    public static ReplayFile fromBytes(byte[] bytes) throws ReplayException {
        return fromBytes(bytes, null);
    }

    /**
     * Parse a replay from an in-memory byte array, carrying a game-data base directory.
     *
     * @param bytes        raw .wowsreplay file bytes
     * @param gameDataBase 游戏数据基础目录（含 data-M.m.p.b/live），可为 null
     */
    public static ReplayFile fromBytes(byte[] bytes, Path gameDataBase) throws ReplayException {
        if (bytes == null || bytes.length < 12) {
            throw new ReplayException("Replay data too short (need at least 12 bytes for header)");
        }

        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        // Parse header
        int magic = buf.getInt();
        if (magic != MAGIC) {
            // Some replays may have different magic; warn but continue
        }
        int blockCount = buf.getInt();

        // Parse metadata block
        int metaLen = buf.getInt();
        if (metaLen <= 0 || metaLen > buf.remaining()) {
            throw new ReplayException("Invalid metadata length: " + metaLen);
        }
        byte[] metaBytes = new byte[metaLen];
        buf.get(metaBytes);
        String rawMeta = new String(metaBytes, StandardCharsets.UTF_8);

        // Parse remaining blocks (blockCount - 1 extra blocks)
        for (int i = 1; i < blockCount && buf.remaining() >= 4; i++) {
            int blockSize = buf.getInt();
            if (blockSize < 0 || blockSize > buf.remaining()) {
                throw new ReplayException("Invalid block size at block " + i + ": " + blockSize);
            }
            buf.position(buf.position() + blockSize);
        }

        // Read decompressed/compressed sizes
        if (buf.remaining() < 8) {
            throw new ReplayException("Missing decompressed/compressed size fields");
        }
        int decompressedSize = buf.getInt();
        int compressedSize = buf.getInt();

        // Remaining bytes are the encrypted+zlib'd packet stream
        byte[] encrypted = new byte[buf.remaining()];
        buf.get(encrypted);

        // Decrypt
        byte[] decrypted;
        try {
            decrypted = decryptBlowfishCbc(encrypted);
        } catch (GeneralSecurityException e) {
            throw new ReplayException("Blowfish decryption failed", e);
        }

        // Decompress
        byte[] packetData;
        try {
            packetData = inflateZlib(decrypted, decompressedSize);
        } catch (Exception e) {
            throw new ReplayException("zlib decompression failed", e);
        }

        // Parse metadata JSON
        ReplayMeta meta;
        try {
            meta = JsonMapper.fromJson(rawMeta, ReplayMeta.class);
        } catch (RuntimeException e) {
            throw new ReplayException("Failed to parse replay metadata JSON", e);
        }
        return new ReplayFile(meta, rawMeta, packetData, gameDataBase);
    }

    /**
     * Parse a replay from a file on disk.
     */
    public static ReplayFile fromFile(Path path) throws ReplayException, IOException {
        return fromFile(path, null);
    }

    /**
     * Parse a replay from a file on disk, carrying a game-data base directory
     * （供 {@code GameDataCache} 按版本定位 data-M.m.p.b/live）。
     */
    public static ReplayFile fromFile(Path path, Path gameDataBase) throws ReplayException, IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return fromBytes(bytes, gameDataBase);
        } catch (ReplayException e) {
            throw new ReplayException("Failed to parse replay: " + path + " — " + e.getMessage(), e);
        }
    }

    /**
     * Parse only the metadata from a replay file, skipping decryption & decompression.
     * Useful for quick inspection without the cost of processing the full packet stream.
     */
    public static ReplayMeta metaFromBytes(byte[] bytes) throws ReplayException {
        if (bytes == null || bytes.length < 12) {
            throw new ReplayException("Replay data too short");
        }
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // magic
        buf.getInt(); // block_count
        int metaLen = buf.getInt();
        if (metaLen <= 0 || metaLen > bytes.length - 12) {
            throw new ReplayException("Invalid metadata length: " + metaLen);
        }
        byte[] metaBytes = new byte[metaLen];
        buf.get(metaBytes);
        String rawMeta = new String(metaBytes, StandardCharsets.UTF_8);
        try {
            return JsonMapper.fromJson(rawMeta, ReplayMeta.class);
        } catch (RuntimeException e) {
            throw new ReplayException("Failed to parse replay metadata JSON", e);
        }
    }

    /**
     * Read only the metadata from a replay file on disk.
     * Only reads the header + metadata block (not the full file).
     */
    public static ReplayMeta metaFromFile(Path path) throws ReplayException, IOException {
        try (var channel = Files.newByteChannel(path)) {
            var headerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(headerBuf);
            headerBuf.flip();
            headerBuf.getInt(); // magic
            headerBuf.getInt(); // block_count
            int metaLen = headerBuf.getInt();
            if (metaLen <= 0 || metaLen > 10 * 1024 * 1024) {
                throw new ReplayException("Invalid metadata length: " + metaLen);
            }
            var metaBuf = ByteBuffer.allocate(metaLen);
            channel.read(metaBuf);
            String rawMeta = new String(metaBuf.array(), StandardCharsets.UTF_8);

            return JsonMapper.fromJson(rawMeta, ReplayMeta.class);
        }
    }

    // ── Crypto ──────────────────────────────────────────────────────────────

    /**
     * Blowfish-CBC decrypt using the hardcoded WG key and all-zero IV.
     *
     */
    static byte[] decryptBlowfishCbc(byte[] encrypted) throws GeneralSecurityException {
        return BlowfishCbcDecryptors.decrypt(encrypted);
    }

    /**
     * zlib decompress using the JDK built-in {@link Inflater}.
     *
     * <p>{@code expectedSize} is used only to size the initial output buffer;
     * the buffer grows automatically when the decompressed data is larger.
     * 与 jlibdeflate 实测逐字节一致（同一 zlib 流，含头 + Adler-32，见
     * docs/graalvm-shared-library-so.md §3.1.1）。</p>
     */
    static byte[] inflateZlib(byte[] compressed, int expectedSize) throws DataFormatException {
        // zlib 流（含头 + Adler-32）
        try (Inflater inflater = new Inflater(false)) {
            byte[] output = new byte[Math.max(expectedSize, compressed.length * 4)];
            int written = 0;
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                if (written == output.length) {
                    output = Arrays.copyOf(output, output.length * 2);
                }
                int n = inflater.inflate(output, written, output.length - written);
                if (n == 0 && !inflater.finished()) {
                    if (inflater.needsInput()) throw new DataFormatException("zlib 数据不完整");
                    if (inflater.needsDictionary()) throw new DataFormatException("zlib 需要字典");
                    // 输出缓冲满：扩容后继续
                }
                written += n;
            }
            return Arrays.copyOf(output, written);
        }
    }
}
