package com.wows.replay.spec.entity;

/**
 * BigWorld entity property visibility flags.
 * Determines when a property is transmitted on the wire.
 * Mirrors Rust's {@code entitydefs::Flags}.
 */
public enum PropertyFlags {
    ALL_CLIENTS,
    CELL_PUBLIC_AND_OWN,
    OWN_CLIENT,
    BASE_AND_CLIENT,
    BASE,
    CELL_PRIVATE,
    CELL_PUBLIC,
    OTHER_CLIENTS;

    /** Parse from .def XML flag string. */
    public static PropertyFlags fromDef(String s) {
        return switch (s) {
            case "ALL_CLIENTS"          -> ALL_CLIENTS;
            case "CELL_PUBLIC_AND_OWN"  -> CELL_PUBLIC_AND_OWN;
            case "OWN_CLIENT"           -> OWN_CLIENT;
            case "BASE_AND_CLIENT"      -> BASE_AND_CLIENT;
            case "BASE"                 -> BASE;
            case "CELL_PRIVATE"         -> CELL_PRIVATE;
            case "CELL_PUBLIC"          -> CELL_PUBLIC;
            case "OTHER_CLIENTS"        -> OTHER_CLIENTS;
            default -> OTHER_CLIENTS; // fallback
        };
    }
}
