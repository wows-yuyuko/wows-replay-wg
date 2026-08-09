package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.spi.DefFileLoader;
import com.shinoaki.wowsreplay.core.types.ArgType;
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
 * var parser = new SpecLoader(loader);
 * List<EntitySpec> specs = parser.parseAll(version);
 * }</pre>
 */
@Slf4j
public final class SpecLoader {

    private final DefFileLoader loader;
    private final DocumentBuilderFactory dbf;

    public SpecLoader(DefFileLoader loader) {
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
        if (entities == null) {
            throw new IOException("scripts/entities.xml 缺少 <ClientServerEntities>——游戏数据版本不匹配或损坏");
        }

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

    /** 递归收集接口定义（含其自身继承的接口），visited 防环。 */
    private DefFile collectInterface(String interfaceName, Map<String, ArgType> aliases,
                                     Set<String> visited) throws IOException {
        if (!visited.add(interfaceName)) return new DefFile();
        var def = parseDefFile("scripts/entity_defs/interfaces/" + interfaceName + ".def", aliases);
        var merged = new DefFile();
        for (var parentName : def.implements_) {
            var parent = collectInterface(parentName, aliases, visited);
            merged.properties.addAll(parent.properties);
            merged.baseMethods.addAll(parent.baseMethods);
            merged.cellMethods.addAll(parent.cellMethods);
            merged.clientMethods.addAll(parent.clientMethods);
        }
        merged.properties.addAll(def.properties);
        merged.baseMethods.addAll(def.baseMethods);
        merged.cellMethods.addAll(def.cellMethods);
        merged.clientMethods.addAll(def.clientMethods);
        return merged;
    }

    // ── Entity resolution ────────────────────────────────────────────────────

    private EntitySpec resolveEntity(String name, DefFile def, Map<String, ArgType> aliases)
        throws IOException {

        // 递归解析接口继承（interface 的 interface 也并入），visited 防环。
        var inherited = new DefFile();
        var visited = new HashSet<String>();
        for (var parentName : def.implements_) {
            var parentDef = collectInterface(parentName, aliases, visited);
            inherited.properties.addAll(parentDef.properties);
            inherited.baseMethods.addAll(parentDef.baseMethods);
            inherited.cellMethods.addAll(parentDef.cellMethods);
            inherited.clientMethods.addAll(parentDef.clientMethods);
        }

        // Merge: inherited first, then own
        var allProperties = new ArrayList<Property>();
        allProperties.addAll(inherited.properties);
        allProperties.addAll(def.properties);

        var allBaseMethods = new ArrayList<Method>();
        allBaseMethods.addAll(inherited.baseMethods);
        allBaseMethods.addAll(def.baseMethods);

        var allCellMethods = new ArrayList<Method>();
        allCellMethods.addAll(inherited.cellMethods);
        allCellMethods.addAll(def.cellMethods);

        var allClientMethods = new ArrayList<Method>();
        allClientMethods.addAll(inherited.clientMethods);
        allClientMethods.addAll(def.clientMethods);

        // 与引擎一致：重复的方法名只保留首次出现的（引擎 loadDataSection 会拒绝
        // 重复定义），否则列表会比客户端多一项，导致后续所有 exposed-method 索引偏移。
        var seen = new HashSet<String>();
        allClientMethods.removeIf(m -> !seen.add(m.name()));

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

        // 仅 client 属性按线尺寸排序（与引擎一致）。internal/base 属性保持
        // 合并声明顺序（现代 wowsunpack 同样不再对它们排序）。
        Comparator<Property> bySize = Comparator.comparingInt(p -> p.propType().sortSize());
        clientProps.sort(bySize);

        return new EntitySpec(name, baseProps, clientProps, internalProps,
            allClientMethods, allBaseMethods, allCellMethods);
    }

    // ── Type parsing ─────────────────────────────────────────────────────────

    /**
     * Parse an {@code <Arg>} or {@code <Type>} node into an {@link ArgType}.
     * Recursively resolves nested types (ARRAY, TUPLE, FIXED_DICT) and aliases.
     *
     * <p>如果节点带 {@code <AllowNone>}（且非 FIXED_DICT，FIXED_DICT 自带 allowNone 标志），
     * 包装为 {@link ArgType.AllowNone}：线路上先读 1 字节存在标志再解析值。</p>
     */
    ArgType parseType(Node node, Map<String, ArgType> aliases) {
        var t = parseTypeInner(node, aliases);
        if (childByName(node, "AllowNone") != null && !(t instanceof ArgType.FixedDict)) {
            return new ArgType.AllowNone(t);
        }
        return t;
    }

    private ArgType parseTypeInner(Node node, Map<String, ArgType> aliases) {
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

        // getTextContent() 会拼接所有后代文本（如 "USER_TYPE\nBLOB\nZippedBlobConverter.converter"、
        // "UINT8 true"）；类型关键字总是第一个 token，对齐 Rust parse_type 的
        // arg.first_child().text().trim()（否则 USER_TYPE/带 AllowNone 的多行类型全落 default→BLOB）。
        var kw = text.split("\\s+")[0];

        return switch (kw) {
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
            case "USER_TYPE" -> {
                // USER_TYPE 带内部 <Type> 时按裸内部类型传输（无长度前缀），
                // 排序时视为变长；无内部 <Type> 时按长度前缀 BLOB 处理。
                var inner = childByName(node, "Type");
                if (inner == null) {
                    log.warn("USER_TYPE 缺少内部 <Type>，回退为 BLOB: node={} text='{}'",
                        node.getNodeName(), text);
                    yield ArgType.Primitive.BLOB;
                }
                yield new ArgType.UserType(parseType(inner, aliases));
            }
            case "MAILBOX" -> ArgType.Primitive.BLOB;
            default -> {
                // ARRAY, TUPLE, FIXED_DICT, or named alias
                if (text.startsWith("ARRAY")) {
                    var ofNode = childByName(node, "of");
                    var sizeNode = childByName(node, "size");
                    var elemType = ofNode != null ? parseType(ofNode, aliases) : ArgType.Primitive.BLOB;
                    OptionalInt fixedSize = OptionalInt.empty();
                    if (sizeNode != null) {
                        try { fixedSize = OptionalInt.of(Integer.parseInt(sizeNode.getTextContent().trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                    yield new ArgType.Array(fixedSize, elemType);
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
                    boolean allowNone = childByName(node, "AllowNone") != null;
                    var propsNode = childByName(node, "Properties");
                    if (propsNode == null) {
                        yield new ArgType.FixedDict(allowNone, List.of());
                    }
                    // Parse inline Properties → FixedDict with field definitions
                    var props = new ArrayList<ArgType.FixedDictProperty>();
                    for (var prop : children(propsNode)) {
                        var propName = prop.getNodeName();
                        var typeNode = childByName(prop, "Type");
                        var propType = typeNode != null ? parseType(typeNode, aliases) : ArgType.Primitive.BLOB;
                        props.add(new ArgType.FixedDictProperty(propName, propType));
                    }
                    yield new ArgType.FixedDict(allowNone, props);
                } else if (aliases.containsKey(kw)) {
                    var resolved = aliases.get(kw);
                    yield new ArgType.NamedType(kw, resolved);
                } else {
                    log.warn("无法识别的 def 类型 '{}'，回退为 BLOB（可能错位后续字段）", text);
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

    private List<Property> parseProperties(Element root, Map<String, ArgType> aliases) {
        var propsNode = childByName(root, "Properties");
        if (propsNode == null) return List.of();
        var result = new ArrayList<Property>();
        int index = 0;
        for (var prop : children(propsNode)) {
            var name = prop.getNodeName();
            var typeNode = childByName(prop, "Type");
            var flagsNode = childByName(prop, "Flags");
            var type = typeNode != null ? parseType(typeNode, aliases) : ArgType.Primitive.BLOB;
            var flags = parseFlags(flagsNode);
            result.add(new Property(name, type, flags, index++));
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

    private List<Method> parseMethodList(Element root, String listName,
                                              Map<String, ArgType> aliases) {
        var listNode = childByName(root, listName);
        if (listNode == null) return new ArrayList<>();
        var result = new ArrayList<Method>();
        int index = 0;
        for (var method : children(listNode)) {
            var name = method.getNodeName();
            var args = parseArgs(method, aliases);
            var vlenNode = childByName(method, "VariableLengthHeaderSize");
            int vlen = 1;
            if (vlenNode != null) {
                try { vlen = Integer.parseInt(vlenNode.getTextContent().trim()); }
                catch (NumberFormatException ignored) {}
            }
            result.add(new Method(name, args, index++, vlen));
        }
        return result;
    }

    private int methodSortSize(Method method, Map<String, ArgType> aliases) {
        // 对标 Rust Method::sort_size：参数尺寸总和 + VariableLengthHeaderSize，
        // 达到 0xFFFF（INFINITY）时仍叠加 vlen。
        int size = method.args().stream().mapToInt(a -> a.argType().sortSize()).sum();
        return size >= 0xFFFF
            ? 0xFFFF + method.variableLengthHeaderSize()
            : size + method.variableLengthHeaderSize();
    }

    private List<Property> filterProperties(List<Property> props,
                                                 PropertyFlags... flags) {
        var result = new ArrayList<Property>();
        int index = 0;
        for (var p : props) {
            if (p.hasAnyFlag(flags)) {
                result.add(new Property(p.name(), p.propType(), p.flags(), index++));
            }
        }
        return result;
    }

    // ── Internal data structures ─────────────────────────────────────────────

    static class DefFile {
        List<Property> properties = new ArrayList<>();
        List<Method> baseMethods = new ArrayList<>();
        List<Method> cellMethods = new ArrayList<>();
        List<Method> clientMethods = new ArrayList<>();
        List<String> implements_ = new ArrayList<>();
    }
}
