package com.wows.replay.spec.entity;

import java.util.List;

/**
 * 从游戏 .def 文件加载的实体类型定义。
 * 将 entity_type（u16 索引）映射到名称、属性和方法。
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
     * 全部属性（用于 EntityCreate 解码）。
     */
    public List<PropertySpec> properties() {
        return clientProperties;
    }
}
