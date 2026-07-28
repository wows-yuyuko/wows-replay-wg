package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wows.replay.core.PacketTypeId;
import com.wows.replay.core.RawPacket;
import com.wows.replay.spec.types.GameClock;

/**
 * A fully decoded replay packet with typed payload.
 *
 * <p>Mirrors Rust's {@code Packet<'replay, 'argtype>}.</p>
 */
public record Packet(
    /** Total packet size in bytes (from header). */
    int packetSize,

    /** Wire packet type (for filtering). */
    PacketTypeId packetType,

    /** Game clock when recorded. */
    GameClock clock,

    /** Decoded payload (null for Invalid/Unknown packets). */
    Object payload,

    /** Raw payload bytes (for debugging). */
    byte[] raw,

    /** Bytes remaining after the parser consumed data (non-empty = parse mismatch). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    byte[] leftover
) {
    /**
     * Create from a RawPacket and decoded payload.
     */
    public static Packet fromRaw(RawPacket raw, Object payload, byte[] leftover) {
        return new Packet(
            raw.packetSize(),
            raw.packetType(),
            raw.clock(),
            payload,
            raw.payload(),
            leftover
        );
    }

    /**
     * Create an invalid packet (parse failure).
     */
    public static Packet invalid(RawPacket raw, String errorMessage) {
        return new Packet(
            raw.packetSize(),
            raw.packetType(),
            raw.clock(),
            new InvalidPayload(errorMessage),
            raw.payload(),
            new byte[0]
        );
    }

    /**
     * Create a packet with unknown payload (type not recognized).
     */
    public static Packet unknown(RawPacket raw) {
        return new Packet(
            raw.packetSize(),
            null,
            raw.clock(),
            raw.payload(),
            raw.payload(),
            new byte[0]
        );
    }

    /**
     * Invalid packet payload (carries error message).
     */
    public record InvalidPayload(String error) {}
}
