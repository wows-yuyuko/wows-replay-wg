package com.wows.replay.spec;

import java.util.List;

/**
 * 从游戏 .def 文件加载的实体类型定义。
 * 将 entity_type（u16 索引）映射到名称、属性和方法。
 */
public record EntitySpec(
    /** Entity type name (e.g. "Avatar", "Vehicle", "Sector") */
    String name,

    /** Base properties (parsed from BasePlayerCreate packet 0x00) */
    List<Property> baseProperties,

    /** Full client properties (parsed from EntityCreate packet 0x05) */
    List<Property> clientProperties,

    /** Internal properties (parsed from CellPlayerCreate packet 0x01) */
    List<Property> internalProperties,

    /** Client-to-cell RPC methods */
    List<Method> clientMethods,

    /** Base-to-cell RPC methods */
    List<Method> baseMethods,

    /** Cell-to-client RPC methods */
    List<Method> cellMethods
) {
    /**
     * 全部属性（用于 EntityCreate 解码）。
     */
    public List<Property> properties() {
        return clientProperties;
    }
}
