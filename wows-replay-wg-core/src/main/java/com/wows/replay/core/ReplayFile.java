package com.wows.replay.core;

import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor;
import com.wows.replay.spec.types.GameClock;
import com.wows.replay.spec.types.Version;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Parsed World of Warships replay file.
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

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /** File magic number (always 0x12345678). Not checked strictly. */
    private static final int MAGIC = 0x12345678;

    /** Blowfish key (16 bytes), identical across all WG game versions. */
    private static final byte[] BLOWFISH_KEY = {
            0x29, (byte) 0xB7, (byte) 0xC9, 0x09, 0x38, 0x3F, (byte) 0x84, (byte) 0x88,
            (byte) 0xFA, (byte) 0x98, (byte) 0xEC, 0x4E, 0x13, 0x19, 0x79, (byte) 0xFB
    };

    private final ReplayMeta meta;
    private final String rawMeta;
    private final byte[] packetData;
    private final Version version;

    private ReplayFile(ReplayMeta meta, String rawMeta, byte[] packetData) {
        this.meta = meta;
        this.rawMeta = rawMeta;
        this.packetData = packetData;
        this.version = Version.fromClientExe(meta.clientVersionFromExe());
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
                .takeWhile(pkt -> pkt != null);
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
        ReplayMeta   meta = JsonMapper.fromJson(rawMeta, ReplayMeta.class);
        return new ReplayFile(meta, rawMeta, packetData);
    }

    /**
     * Parse a replay from a file on disk.
     */
    public static ReplayFile fromFile(Path path) throws ReplayException, IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return fromBytes(bytes);
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
        return JsonMapper.mapper()
                .readerFor(ReplayMeta.class)
                .readValue(rawMeta);

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
     * <p>Implements CBC manually: each plaintext block (8 bytes) is XORed with
     * the previous ciphertext block. The IV is all zeros.</p>
     */
    static byte[] decryptBlowfishCbc(byte[] encrypted) throws GeneralSecurityException {
        // Pad to 8-byte boundary for Blowfish (64-bit block cipher)
        int paddedLen = ((encrypted.length + 7) / 8) * 8;
        byte[] padded = Arrays.copyOf(encrypted, paddedLen);

        // Java's standard Blowfish uses "Blowfish/ECB/NoPadding" (BouncyCastle provides it)
        // But the standard approach: use BouncyCastle's provider or JCA with BouncyCastle installed
        Cipher cipher = Cipher.getInstance("Blowfish/ECB/NoPadding", "BC");
        var keySpec = new SecretKeySpec(BLOWFISH_KEY, "Blowfish");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);

        // CBC with zero IV done manually
        byte[] decrypted = new byte[paddedLen];
        byte[] previous = new byte[8]; // all-zero IV

        for (int offset = 0; offset < paddedLen; offset += 8) {
            byte[] block = new byte[8];
            System.arraycopy(padded, offset, block, 0, 8);
            byte[] decryptedBlock = cipher.doFinal(block);
            for (int j = 0; j < 8; j++) {
                decrypted[offset + j] = (byte) (decryptedBlock[j] ^ previous[j]);
            }
            System.arraycopy(decrypted, offset, previous, 0, 8);
        }

        return decrypted;
    }

    /**
     * zlib decompress using jlibdeflate.
     *
     * <p>Uses the extended decompression API so the exact uncompressed size
     * isn't required — {@code expectedSize} is used only to size the output
     * buffer.  The decompressor is closed via try-with-resources.</p>
     */
    static byte[] inflateZlib(byte[] compressed, int expectedSize) {
        try (var decompressor = new LibdeflateDecompressor()) {
            byte[] output = new byte[Math.max(expectedSize, compressed.length * 4)];
            var result = decompressor.zlibDecompressEx(compressed, 0, compressed.length,
                output, 0, output.length);
            int written = result.outputBytesProduced();
            if (written < output.length) {
                return Arrays.copyOf(output, written);
            }
            return output;
        }
    }
}
