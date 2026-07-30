package com.wows.replay.core;

import com.wows.replay.core.types.GameClock;
import com.wows.replay.core.types.Version;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Sans-io iterator over a decrypted replay packet stream.
 *
 * <p>Parses only packet headers (size + type + clock), never the entity-spec-dependent
 * payloads. Each packet header is 12 bytes: [size: u32][type: u32][clock: f32].</p>
 *
 * <p>On a malformed/truncated stream, the iterator stops (returns false from hasNext).</p>
 *
 * <p>Thread-safe: each call to {@link #next()} advances the internal position.</p>
 */
public class RawPacketIterator implements Iterator<RawPacket> {

    /** Minimum packet header size: size(u32) + type(u32) + clock(f32) = 12 bytes */
    private static final int HEADER_SIZE = 12;

    private final ByteBuffer buffer;
    private final Version version;
    private RawPacket nextPacket;
    private boolean nextReady;
    private boolean done;

    /**
     * Create an iterator assuming the modern packet-ID layout.
     */
    public RawPacketIterator(byte[] packetData) {
        this(packetData, null);
    }

    /**
     * Create an iterator using the packet-ID layout matching the given version.
     *
     * @param packetData decrypted, decompressed packet stream
     * @param version    replay game version (null = modern layout)
     */
    public RawPacketIterator(byte[] packetData, Version version) {
        this.buffer = ByteBuffer.wrap(packetData).order(ByteOrder.LITTLE_ENDIAN);
        this.version = version;
        this.done = packetData == null || packetData.length < HEADER_SIZE;
        advance();
    }

    private void advance() {
        if (done || buffer.remaining() < HEADER_SIZE) {
            done = true;
            nextPacket = null;
            nextReady = true;
            return;
        }

        try {
            int packetSize = buffer.getInt();          // u32 little-endian
            int rawType = buffer.getInt();              // u32 little-endian
            float rawClock = buffer.getFloat();         // f32 little-endian

            // Guard against corrupt data
            if (packetSize < 0 || packetSize > buffer.remaining()) {
                done = true;
                nextPacket = null;
                nextReady = true;
                return;
            }

            byte[] payload = new byte[packetSize];
            if (packetSize > 0) {
                buffer.get(payload);
            }

            PacketTypeId typeId = version != null
                ? PacketTypeId.fromRaw(rawType, version)
                : PacketTypeId.fromRawModern(rawType);

            nextPacket = new RawPacket(packetSize, typeId, new GameClock(rawClock), payload);
        } catch (Exception e) {
            done = true;
            nextPacket = null;
        }
        nextReady = true;
    }

    @Override
    public boolean hasNext() {
        return nextPacket != null;
    }

    @Override
    public RawPacket next() {
        if (!hasNext()) throw new NoSuchElementException();
        var pkt = nextPacket;
        advance();
        return pkt;
    }
}
