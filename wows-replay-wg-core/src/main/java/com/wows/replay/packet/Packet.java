package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wows.replay.PacketTypeId;
import com.wows.replay.packet.RawPacket;
import com.wows.replay.model.GameClock;

/**
 * 完全解码的带类型载荷的回放包。
 *
 * <p>对标 Rust {@code Packet<'replay, 'argtype>}.</p>
 */
public record Packet(
    /** 包总大小（来自头部）。 */
    int packetSize,

    /** Wire packet type (for filtering). */
    PacketTypeId packetType,

    /** 记录时的游戏时钟。 */
    GameClock clock,

    /** 已解码载荷。 */
    Object payload,

    /** 原始载荷字节（调试用）。 */
    byte[] raw,

    /** Bytes remaining after the parser consumed data (non-empty = parse mismatch). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    byte[] leftover
) {
    /**
     * 从 RawPacket 和已解码载荷创建。
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
     * 创建无效包（解析失败）。
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
     * 创建未知载荷包。
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
     * 无效包载荷。
     */
    public record InvalidPayload(String error) {}
}
