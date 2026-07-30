package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wows.replay.core.PacketTypeId;
import com.wows.replay.core.RawPacket;
import com.wows.replay.core.types.GameClock;

/**
 * 瀹屽叏瑙ｇ爜鐨勫甫绫诲瀷杞借嵎鐨勫洖鏀惧寘銆?
 *
 * <p>瀵规爣 Rust {@code Packet<'replay, 'argtype>}.</p>
 */
public record Packet(
    /** 鍖呮€诲ぇ灏忥紙鏉ヨ嚜澶撮儴锛夈€?*/
    int packetSize,

    /** Wire packet type (for filtering). */
    PacketTypeId packetType,

    /** 璁板綍鏃剁殑娓告垙鏃堕挓銆?*/
    GameClock clock,

    /** 宸茶В鐮佽浇鑽枫€?*/
    Object payload,

    /** 鍘熷杞借嵎瀛楄妭锛堣皟璇曠敤锛夈€?*/
    byte[] raw,

    /** Bytes remaining after the parser consumed data (non-empty = parse mismatch). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    byte[] leftover
) {
    /**
     * 浠?RawPacket 鍜屽凡瑙ｇ爜杞借嵎鍒涘缓銆?
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
     * 鍒涘缓鏃犳晥鍖咃紙瑙ｆ瀽澶辫触锛夈€?
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
     * 鍒涘缓鏈煡杞借嵎鍖呫€?
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
     * 鏃犳晥鍖呰浇鑽枫€?
     */
    public record InvalidPayload(String error) {}
}
