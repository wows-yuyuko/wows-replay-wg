package com.wows.replay.spec.entity;

/**
 * BigWorld 实体属性可见性标志。
 * 确定属性何时在线路上传输。
 * 对标 Rust {@code entitydefs::Flags}.
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

    /** 从 .def XML 标志字符串解析。 */
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
