package com.wows.replay.gamedata;

import com.wows.replay.core.entity.*;
import com.wows.replay.core.rpc.ArgType;
import com.wows.replay.core.spi.DefFileLoader;
import com.wows.replay.core.types.Version;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Parses BigWorld .def entity definition files and alias/entities XML.
 *
 * <p>This is a concrete implementation of the def→EntitySpec pipeline,
 * using JDK's built-in XML parser (no external XML library needed).
 * Mirrors Rust {@code wowsunpack::rpc::entitydefs} + {@code typedefs}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DefFileLoader loader = path -> Files.readAllBytes(gameDir.resolve(path));
 * var parser = new XmlDefParser(loader);
 * List<EntitySpec> specs = parser.parseAll(version);
 * }</pre>
 */
@Slf4j
public final class XmlDefParser {

    private final DefFileLoader loader;
    private final DocumentBuilderFactory dbf;

    public XmlDefParser(DefFileLoader loader) {
        this.loader = loader;
        this.dbf = DocumentBuilderFactory.newInstance();
        dbf.setIgnoringComments(true);
        dbf.setIgnoringElementContentWhitespace(true);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Parse all entity specs for a game version.
     * Loads alias.xml, entities.xml, and all referenced .def files.
     */
    public List<EntitySpec> parseAll(Version version) throws IOException {
        Map<String, ArgType> aliases;
        try {
            aliases = parseAliases();
        } catch (IOException e) {
            log.error("无法加载 scripts/entity_defs/alias.xml: {}", e.getMessage());
            throw e;
        }

        List<String> entityNames;
        try {
            entityNames = parseEntityList();
        } catch (IOException e) {
            log.error("无法加载 scripts/entities.xml: {}", e.getMessage());
            throw e;
        }

        log.info("发现 {} 个实体类型，开始加载 .def 文件...", entityNames.size());

        var result = new ArrayList<EntitySpec>(entityNames.size());
        int skipped = 0;
        for (var name : entityNames) {
            try {
                var def = parseDefFile("scripts/entity_defs/" + name + ".def", aliases);
                result.add(resolveEntity(name, def, aliases));
            } catch (IOException e) {
                skipped++;
            }
        }

        if (skipped > 0) {
            log.warn("跳过 {} 个无法加载的 .def 文件", skipped);
        }
        log.info("成功加载 {} 个实体规范", result.size());
        return result;
    }

    // ── Alias parsing ────────────────────────────────────────────────────────

    /**
     * Parse {@code scripts/entity_defs/alias.xml} into a type alias map.
     */
    Map<String, ArgType> parseAliases() throws IOException {
        var doc = parseXml(loader.get("scripts/entity_defs/alias.xml"));
        var root = doc.getDocumentElement();
        var aliases = new LinkedHashMap<String, ArgType>();

        for (var child : children(root)) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            var name = child.getNodeName();
            aliases.put(name, parseType(child, aliases));
        }
        return aliases;
    }

    // ── Entity list ──────────────────────────────────────────────────────────

    /**
     * Parse {@code scripts/entities.xml} to get the ordered entity name list.
     */
    List<String> parseEntityList() throws IOException {
        var doc = parseXml(loader.get("scripts/entities.xml"));
        // getDocumentElement() 返回的就是 <root>，直接使用
        var root = doc.getDocumentElement();
        var entities = childByName(root, "ClientServerEntities");
        if (entities == null) return List.of();

        var names = new ArrayList<String>();
        for (var child : children(entities)) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                names.add(child.getNodeName());
            }
        }
        return names;
    }

    // ── .def file parsing ────────────────────────────────────────────────────

    DefFile parseDefFile(String path, Map<String, ArgType> aliases) throws IOException {
        var doc = parseXml(loader.get(path));
        // doc.getDocumentElement() 就是 <root>，直接使用
        var root = doc.getDocumentElement();

        var def = new DefFile();
        def.implements_ = parseImplements(root);
        def.properties = parseProperties(root, aliases);
        def.baseMethods = parseMethodList(root, "BaseMethods", aliases);
        def.cellMethods = parseMethodList(root, "CellMethods", aliases);
        def.clientMethods = parseMethodList(root, "ClientMethods", aliases);

        // Sort client methods by wire size (Rust sorts for dispatch)
        def.clientMethods.sort(Comparator.comparingInt(m -> methodSortSize(m, aliases)));
        return def;
    }

    // ── Entity resolution ────────────────────────────────────────────────────

    private EntitySpec resolveEntity(String name, DefFile def, Map<String, ArgType> aliases)
        throws IOException {

        // Resolve interface inheritance (one level of indirection)
        var inherited = new DefFile();
        for (var parentName : def.implements_) {
            var parentDef = parseDefFile(
                "scripts/entity_defs/interfaces/" + parentName + ".def", aliases);
            inherited.properties.addAll(parentDef.properties);
            inherited.baseMethods.addAll(parentDef.baseMethods);
            inherited.cellMethods.addAll(parentDef.cellMethods);
            inherited.clientMethods.addAll(parentDef.clientMethods);
        }

        // Merge: inherited first, then own
        var allProperties = new ArrayList<PropertySpec>();
        allProperties.addAll(inherited.properties);
        allProperties.addAll(def.properties);

        var allBaseMethods = new ArrayList<MethodSpec>();
        allBaseMethods.addAll(inherited.baseMethods);
        allBaseMethods.addAll(def.baseMethods);

        var allCellMethods = new ArrayList<MethodSpec>();
        allCellMethods.addAll(inherited.cellMethods);
        allCellMethods.addAll(def.cellMethods);

        var allClientMethods = new ArrayList<MethodSpec>();
        allClientMethods.addAll(inherited.clientMethods);
        allClientMethods.addAll(def.clientMethods);

        // Sort client methods by wire size
        allClientMethods.sort(Comparator.comparingInt(m -> methodSortSize(m, aliases)));

        // Debug: log Avatar properties
        if ("Avatar".equals(name)) {
            log.debug("Avatar 属性总数={} (自身={} 继承={})",
                allProperties.size(), def.properties.size(), inherited.properties.size());
            if (log.isTraceEnabled()) {
                for (var p : allProperties) {
                    log.trace("  {} : {} (type={})", p.name(), p.flags(), p.propType().typeName());
                }
            }
        }

        // Filter properties by visibility flags
        var internalProps = filterProperties(allProperties,
            PropertyFlags.ALL_CLIENTS, PropertyFlags.OTHER_CLIENTS,
            PropertyFlags.OWN_CLIENT, PropertyFlags.CELL_PUBLIC_AND_OWN);

        var baseProps = filterProperties(allProperties,
            PropertyFlags.BASE_AND_CLIENT);

        var clientProps = filterProperties(allProperties,
            PropertyFlags.ALL_CLIENTS, PropertyFlags.BASE_AND_CLIENT,
            PropertyFlags.OTHER_CLIENTS, PropertyFlags.OWN_CLIENT,
            PropertyFlags.CELL_PUBLIC_AND_OWN);

        if ("Avatar".equals(name) || "Account".equals(name)) {
            log.debug("{} baseProps={} clientProps={} internalProps={}",
                name, baseProps.size(), clientProps.size(), internalProps.size());
            if (name.equals("Account")) {
                log.debug("Account allProperties={} (own={} inherited={})",
                    allProperties.size(), def.properties.size(), inherited.properties.size());
                log.debug("Account 继承接口: {}", def.implements_);
            }
        }

        // Sort by wire size
        Comparator<PropertySpec> bySize = Comparator.comparingInt(p -> p.propType().sortSize());
        internalProps.sort(bySize);
        baseProps.sort(bySize);
        clientProps.sort(bySize);

        return new EntitySpec(name, baseProps, clientProps, internalProps,
            allClientMethods, allBaseMethods, allCellMethods);
    }

    // ── Type parsing ─────────────────────────────────────────────────────────

    /**
     * Parse an {@code <Arg>} or {@code <Type>} node into an {@link ArgType}.
     * Recursively resolves nested types (ARRAY, TUPLE, FIXED_DICT) and aliases.
     */
    ArgType parseType(Node node, Map<String, ArgType> aliases) {
        var text = node.getTextContent().trim();
        if (text.isBlank()) {
            // Try child element (e.g. <Type><Arg>...</Arg></Type>)
            for (var child : children(node)) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    return parseType(child, aliases);
                }
            }
            return ArgType.Primitive.BLOB;
        }

        return switch (text) {
            case "UINT8"     -> ArgType.Primitive.UINT8;
            case "UINT16"    -> ArgType.Primitive.UINT16;
            case "UINT32"    -> ArgType.Primitive.UINT32;
            case "UINT64"    -> ArgType.Primitive.UINT64;
            case "INT8"      -> ArgType.Primitive.INT8;
            case "INT16"     -> ArgType.Primitive.INT16;
            case "INT32"     -> ArgType.Primitive.INT32;
            case "INT64"     -> ArgType.Primitive.INT64;
            case "FLOAT32", "FLOAT" -> ArgType.Primitive.FLOAT;
            case "FLOAT64"   -> ArgType.Primitive.DOUBLE;
            case "STRING"    -> ArgType.Primitive.STRING;
            case "BOOLEAN", "BOOL" -> ArgType.Primitive.BOOL;
            case "BLOB"      -> ArgType.Primitive.BLOB;
            case "PYTHON"    -> ArgType.Primitive.PYTHON;
            case "VECTOR2"   -> ArgType.Primitive.VECTOR2;
            case "VECTOR3"   -> ArgType.Primitive.VECTOR3;
            case "VECTOR4"   -> ArgType.Primitive.VECTOR4;
            case "UNICODE_STRING" -> ArgType.Primitive.STRING;
            case "USER_TYPE", "MAILBOX" -> ArgType.Primitive.BLOB;
            default -> {
                // ARRAY, TUPLE, FIXED_DICT, or named alias
                if (text.startsWith("ARRAY")) {
                    var ofNode = childByName(node, "of");
                    var sizeNode = childByName(node, "size");
                    var elemType = ofNode != null ? parseType(ofNode, aliases) : ArgType.Primitive.BLOB;
                    yield new ArgType.Array(elemType);
                } else if (text.startsWith("TUPLE")) {
                    var ofNode = childByName(node, "of");
                    var elemType = ofNode != null ? parseType(ofNode, aliases) : ArgType.Primitive.BLOB;
                    int size = 1;
                    if (childByName(node, "size") != null) {
                        try { size = Integer.parseInt(childByName(node, "size").getTextContent().trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                    // Expand TUPLE<of> TYPE <size> N into N copies of TYPE
                    var elems = new ArrayList<ArgType>(size);
                    for (int i = 0; i < size; i++) elems.add(elemType);
                    yield new ArgType.Tuple(elems);
                } else if (text.startsWith("FIXED_DICT")) {
                    var propsNode = childByName(node, "Properties");
                    if (propsNode == null) {
                        yield new ArgType.NamedType(text);
                    }
                    // FIXED_DICT with inline Properties → treat as named for now
                    yield new ArgType.NamedType(text);
                } else if (aliases.containsKey(text)) {
                    var resolved = aliases.get(text);
                    yield new ArgType.NamedType(text, resolved);
                } else {
                    yield ArgType.Primitive.BLOB;
                }
            }
        };
    }

    // ── XML helpers ──────────────────────────────────────────────────────────

    private Document parseXml(byte[] data) throws IOException {
        try {
            var builder = dbf.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new IOException("Failed to parse XML", e);
        }
    }

    private Element childByName(Node parent, String name) {
        for (var child : children(parent)) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                && child.getNodeName().equals(name)) {
                return (Element) child;
            }
        }
        return null;
    }

    private List<Element> children(Node parent) {
        var list = new ArrayList<Element>();
        var nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            var node = nl.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                list.add((Element) node);
            }
        }
        return list;
    }

    // ── Def structure helpers ────────────────────────────────────────────────

    private List<String> parseImplements(Element root) {
        var impl = childByName(root, "Implements");
        if (impl == null) return List.of();
        var result = new ArrayList<String>();
        for (var child : children(impl)) {
            result.add(child.getTextContent().trim());
        }
        return result;
    }

    private List<PropertySpec> parseProperties(Element root, Map<String, ArgType> aliases) {
        var propsNode = childByName(root, "Properties");
        if (propsNode == null) return List.of();
        var result = new ArrayList<PropertySpec>();
        int index = 0;
        for (var prop : children(propsNode)) {
            var name = prop.getNodeName();
            var typeNode = childByName(prop, "Type");
            var flagsNode = childByName(prop, "Flags");
            var type = typeNode != null ? parseType(typeNode, aliases) : ArgType.Primitive.BLOB;
            var flags = parseFlags(flagsNode);
            result.add(new PropertySpec(name, type, flags, index++));
        }
        return result;
    }

    /** 解析复合标记 — Flags 包含多个子元素如 <BASE_AND_CLIENT/><ALL_CLIENTS/> */
    private Set<PropertyFlags> parseFlags(Element flagsNode) {
        if (flagsNode == null) return Set.of(PropertyFlags.ALL_CLIENTS);
        var result = EnumSet.noneOf(PropertyFlags.class);
        for (var child : children(flagsNode)) {
            var flag = PropertyFlags.fromDef(child.getNodeName());
            if (flag != null) result.add(flag);
        }
        // 如果没有子元素，尝试解析文本内容
        if (result.isEmpty()) {
            var text = flagsNode.getTextContent().trim();
            if (!text.isBlank()) {
                var flag = PropertyFlags.fromDef(text);
                if (flag != null) result.add(flag);
            }
        }
        return result.isEmpty() ? Set.of(PropertyFlags.ALL_CLIENTS) : result;
    }

    private List<ArgSpec> parseArgs(Element methodNode, Map<String, ArgType> aliases) {
        // Args can be inline <Arg> children or inside an <Args> wrapper
        var result = new ArrayList<ArgSpec>();
        int index = 0;

        // Check for <Args> wrapper
        var argsWrapper = childByName(methodNode, "Args");
        if (argsWrapper != null) {
            for (var arg : children(argsWrapper)) {
                result.add(new ArgSpec(arg.getNodeName(), parseType(arg, aliases), index++));
            }
        } else {
            // Inline <Arg> elements
            for (var arg : children(methodNode)) {
                if (arg.getNodeName().equals("Arg")) {
                    result.add(new ArgSpec("arg" + index, parseType(arg, aliases), index++));
                }
            }
        }
        return result;
    }

    private List<MethodSpec> parseMethodList(Element root, String listName,
                                              Map<String, ArgType> aliases) {
        var listNode = childByName(root, listName);
        if (listNode == null) return new ArrayList<>();
        var result = new ArrayList<MethodSpec>();
        int index = 0;
        for (var method : children(listNode)) {
            var name = method.getNodeName();
            var args = parseArgs(method, aliases);
            result.add(new MethodSpec(name, args, index++));
        }
        return result;
    }

    private int methodSortSize(MethodSpec method, Map<String, ArgType> aliases) {
        int size = method.args().stream().mapToInt(a -> a.argType().sortSize()).sum();
        return Math.min(size, 0xFFFF);
    }

    private List<PropertySpec> filterProperties(List<PropertySpec> props,
                                                 PropertyFlags... flags) {
        var result = new ArrayList<PropertySpec>();
        int index = 0;
        for (var p : props) {
            if (p.hasAnyFlag(flags)) {
                result.add(new PropertySpec(p.name(), p.propType(), p.flags(), index++));
            }
        }
        return result;
    }

    // ── Internal data structures ─────────────────────────────────────────────

    static class DefFile {
        List<PropertySpec> properties = new ArrayList<>();
        List<MethodSpec> baseMethods = new ArrayList<>();
        List<MethodSpec> cellMethods = new ArrayList<>();
        List<MethodSpec> clientMethods = new ArrayList<>();
        List<String> implements_ = new ArrayList<>();
    }
}
