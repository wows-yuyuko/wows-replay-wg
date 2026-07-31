package com.wows.replay.decode;

import com.wows.replay.model.EntityId;
import com.wows.replay.types.ArgValue;
import com.wows.replay.packet.EntityPropertyPacket;

import java.util.Optional;

/**
 * Decodes {@link EntityPropertyPacket} into a semantic {@link PropertyChange}.
 *
 * <p>The {@link Kind} enum is the single source of truth for property-name→semantics
 * mapping. Ingest code switches on {@code Kind} instead of property name strings,
 * eliminating duplicated property-switch logic.</p>
 */
public final class PropertyDecoder {

    private PropertyDecoder() {}

    /**
     * Decode a property packet into a semantic property change.
     *
     * @return the change, or {@link Optional#empty()} if the property is unrecognized
     */
    public static Optional<PropertyChange> decode(EntityPropertyPacket packet) {
        String name = packet.property();
        ArgValue value = packet.value();
        Kind kind = Kind.fromPropertyName(name);
        return Optional.of(new PropertyChange(packet.entityId(), name, kind, value));
    }

    // ── PropertyChange ──────────────────────────────────────────────────

    /**
     * A decoded property change with semantic kind.
     */
    public record PropertyChange(EntityId entityId, String name, Kind kind, ArgValue value) {}

    // ── Kind ────────────────────────────────────────────────────────────

    /**
     * Semantic kind of an entity property.
     *
     * <p>Ingest handlers switch on this enum instead of matching property name
     * strings, providing compile-time exhaustiveness and a single source of truth.</p>
     */
    public enum Kind {
        HEALTH,
        MAX_HEALTH,
        TEAM_ID,
        IS_ALIVE,
        IS_INVISIBLE,
        MAX_DURATION,
        PLAYED_DURATION,
        EXTRA_DURATION,
        FINISH_TYPE,
        MATCH_RESULT,
        BATTLE_RESULT,  // BattleLogic: { winnerTeamId, finishReason } — 15.x win/finish source
        BATTLE_STAGE,   // BattleLogic: numeric battle stage (0=Waiting, 1=Battle, ...)
        TIME_LEFT,      // BattleLogic: seconds remaining
        STATE,        // complex state dict (battle logic, control points, etc.)
        SHIP_CONFIG,  // ship configuration blob
        VEHICLE_ID,   // vehicle game param ID
        OWNER_ID,     // owner entity ID (e.g. Vehicle → Avatar)
        OTHER;        // unrecognized — ingest may still want the raw value

        /**
         * Map a BigWorld property name to its semantic kind.
         */
        public static Kind fromPropertyName(String name) {
            if (name == null) return OTHER;
            return switch (name) {
                case "health", "currentHealth", "healthPoints"     -> HEALTH;
                case "maxHealth", "maxHealthPoints"                -> MAX_HEALTH;
                case "teamId", "teamID"                            -> TEAM_ID;
                case "isAlive", "isAlive_"                         -> IS_ALIVE;
                case "isInvisible", "isInvisible_"                 -> IS_INVISIBLE;
                case "maxDuration"                                 -> MAX_DURATION;
                case "playedDuration"                              -> PLAYED_DURATION;
                case "extraDuration"                               -> EXTRA_DURATION;
                case "finishType", "finishType_"                   -> FINISH_TYPE;
                case "matchResult"                                 -> MATCH_RESULT;
                case "battleResult"                                -> BATTLE_RESULT;
                case "battleStage", "battleStage_"                 -> BATTLE_STAGE;
                case "timeLeft"                                    -> TIME_LEFT;
                case "state", "state_"                             -> STATE;
                case "shipConfig", "shipConfigDump"                -> SHIP_CONFIG;
                case "vehicleID", "vehicleId"                      -> VEHICLE_ID;
                case "ownerID", "ownerId", "ownerEntityId"         -> OWNER_ID;
                default                                            -> OTHER;
            };
        }
    }
}
