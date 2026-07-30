package com.wows.replay.core;

import com.wows.replay.core.types.GameClock;

/**
 * A packet with its 12-byte header parsed but the payload left raw.
 *
 * <p>Produced by {@link RawPacketIterator}. Lets callers walk a decrypted replay
 * stream without entity definitions: the header is always available.</p>
 *
 * @param packetSize payload size in bytes
 * @param packetType wire type identifier
 * @param clock      game clock when this packet was recorded
 * @param payload    raw payload bytes (may be empty)
 */
public record RawPacket(
    int packetSize,
    PacketTypeId packetType,
    GameClock clock,
    byte[] payload
) {
    /** Returns true if the packet type was unrecognized by the version-aware mapper. */
    public boolean isUnknown() {
        return packetType == null;
    }

    @Override
    public String toString() {
        var typeStr = packetType != null ? packetType.displayName() + "(0x" + Integer.toHexString(packetType.raw()) + ")" : "UNKNOWN";
        return "RawPacket[type=" + typeStr + ", clock=" + clock + ", size=" + packetSize + "]";
    }
}
