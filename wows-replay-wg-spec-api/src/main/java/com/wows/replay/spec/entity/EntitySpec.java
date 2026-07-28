package com.wows.replay.spec.entity;

import java.util.List;

/**
 * Entity type definition loaded from game .def files.
 * Maps entity_type (u16 index into specs array) to its name,
 * properties, and methods.
 */
public record EntitySpec(
    /** Entity type name (e.g. "Avatar", "Vehicle", "Sector") */
    String name,

    /** Base properties (parsed from BasePlayerCreate packet 0x00) */
    List<PropertySpec> baseProperties,

    /** Full client properties (parsed from EntityCreate packet 0x05) */
    List<PropertySpec> clientProperties,

    /** Internal properties (parsed from CellPlayerCreate packet 0x01) */
    List<PropertySpec> internalProperties,

    /** Client-to-cell RPC methods */
    List<MethodSpec> clientMethods,

    /** Base-to-cell RPC methods */
    List<MethodSpec> baseMethods,

    /** Cell-to-client RPC methods */
    List<MethodSpec> cellMethods
) {
    /**
     * Convenience: all properties (used for EntityCreate decoding).
     */
    public List<PropertySpec> properties() {
        return clientProperties;
    }
}
