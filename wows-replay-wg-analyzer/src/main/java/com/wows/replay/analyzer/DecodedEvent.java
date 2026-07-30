package com.wows.replay.analyzer;

import com.wows.replay.packets.NamedArgs;

import java.util.List;

/**
 * Decoded event payload — extracted from EntityMethod args by the decoder layer.
 *
 * <p>Mirrors wows-toolkit's {@code DecodedPacketPayload}.  Each variant carries
 * only the typed business-logic fields; all {@code instanceof} and positional
 * arg access lives in {@link #decode(String, NamedArgs)} so handlers never see
 * raw args.</p>
 */
public sealed interface DecodedEvent {

    /** {@code receiveDamagesOnShip}: list of individual damage entries. */
    record DamageStat(List<Entry> entries) implements DecodedEvent {
        public record Entry(int aggressorEntityId, float amount) {}
    }

    /** {@code receiveVehicleDeath}. */
    record ShipDestroyed(int victimEntityId, int killerEntityId, int cause) implements DecodedEvent {}

    /** {@code onChatMessage}. */
    record ChatMessage(int senderId, String channel, String message) implements DecodedEvent {}

    /** {@code onConsumableUsed} (15.2+ blob format). */
    record ConsumableUsed(int consumableId, float duration) implements DecodedEvent {}

    /** {@code onArenaStateReceived}: pickled players-states blob. */
    record ArenaState(byte[] playersBlob) implements DecodedEvent {}

    /** {@code onNewPlayerSpawnedInBattle}: pickled players-data blob. */
    record PlayerSpawned(byte[] playersBlob) implements DecodedEvent {}

    // ── Decoder ──────────────────────────────────────────────────────────

    /**
     * Decode EntityMethod args into a typed event.
     *
     * @return the decoded event, or null if the method is unrecognised or args are malformed.
     */
    static DecodedEvent decode(String method, NamedArgs args) {
        return switch (method) {
            case "receiveDamagesOnShip" -> {
                if (args.isEmpty() || !(args.getFirst() instanceof com.wows.replay.core.rpc.ArgValue.ArrayVal arr))
                    yield null;
                var entries = arr.elements().stream()
                    .filter(e -> e instanceof com.wows.replay.core.rpc.ArgValue.DictVal)
                    .map(e -> {
                        var d = ((com.wows.replay.core.rpc.ArgValue.DictVal) e).entries();
                        return new DamageStat.Entry(
                            intFromArg(d.get("vehicleID")), floatFromArg(d.get("damage")));
                    })
                    .toList();
                yield entries.isEmpty() ? null : new DamageStat(entries);
            }
            case "receiveVehicleDeath" -> {
                if (args.size() < 2) yield null;
                yield new ShipDestroyed(
                    intFromArg(args.get(0)), intFromArg(args.get(1)),
                    args.size() >= 3 ? intFromArg(args.get(2)) : 0);
            }
            case "onChatMessage" -> {
                if (args.size() < 4) yield null;
                yield new ChatMessage(
                    intFromArg(args.get(0)),
                    args.get(1) instanceof com.wows.replay.core.rpc.ArgValue.StrVal sv ? sv.value() : "",
                    args.get(2) instanceof com.wows.replay.core.rpc.ArgValue.StrVal sv ? sv.value() : "");
            }
            case "onConsumableUsed" -> {
                if (args.isEmpty()) yield null;
                var a0 = args.getFirst();
                if (a0 instanceof com.wows.replay.core.rpc.ArgValue.BlobVal blob) {
                    // 15.2+ packed struct (see RichExtractor.handleConsumable)
                    byte[] b = blob.value();
                    if (b.length < 2) yield null;
                    int usageType = b[0] & 0xFF;
                    if (usageType == 0) yield null; // NONE
                    int consumableId = b[1] & 0xFF;
                    float duration = args.size() >= 2 ? floatFromArg(args.get(1)) : 0f;
                    yield new ConsumableUsed(consumableId, duration);
                }
                yield null;
            }
            case "onArenaStateReceived" -> {
                if (args.has("playersStates")
                    && args.get("playersStates") instanceof com.wows.replay.core.rpc.ArgValue.BlobVal blob)
                    yield new ArenaState(blob.value());
                // fallback: positional access
                if (args.size() >= 4 && args.get(3) instanceof com.wows.replay.core.rpc.ArgValue.BlobVal blob)
                    yield new ArenaState(blob.value());
                yield null;
            }
            case "onNewPlayerSpawnedInBattle" -> {
                if (args.has("playersData")
                    && args.get("playersData") instanceof com.wows.replay.core.rpc.ArgValue.BlobVal blob)
                    yield new PlayerSpawned(blob.value());
                // fallback: positional access
                if (!args.isEmpty() && args.getFirst() instanceof com.wows.replay.core.rpc.ArgValue.BlobVal blob)
                    yield new PlayerSpawned(blob.value());
                yield null;
            }
            default -> null;
        };
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    private static int intFromArg(com.wows.replay.core.rpc.ArgValue v) {
        return switch (v) {
            case com.wows.replay.core.rpc.ArgValue.IntVal iv -> (int) iv.value();
            case com.wows.replay.core.rpc.ArgValue.FloatVal fv -> (int) fv.value();
            default -> 0;
        };
    }

    private static float floatFromArg(com.wows.replay.core.rpc.ArgValue v) {
        return switch (v) {
            case com.wows.replay.core.rpc.ArgValue.FloatVal fv -> (float) fv.value();
            case com.wows.replay.core.rpc.ArgValue.IntVal iv -> (float) iv.value();
            default -> 0f;
        };
    }
}
