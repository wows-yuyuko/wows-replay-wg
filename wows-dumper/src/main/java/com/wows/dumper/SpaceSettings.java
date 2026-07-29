package com.wows.dumper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析地图的 {@code space.settings} XML，计算小地图坐标范围。
 * 对标 Rust {@code position::parse_space_settings}。
 *
 * <p>公式：
 * <pre>
 * chunks_x  = maxX - minX + 1
 * chunks_y  = maxY - minY + 1
 * space_w   = (chunks_x - 4) × chunkSize
 * space_h   = (chunks_y - 4) × chunkSize
 * space_size = max(space_w, space_h)
 * </pre>
 *
 * <p>一个 chunk = 100m（默认值）。
 */
public final class SpaceSettings {

    private SpaceSettings() {}

    /**
     * 解析指定地图的 space.settings 并返回 space_size。
     *
     * @param gameData 游戏数据目录（data-{version}/live/）
     * @param mapName  回放元数据中的 mapName（如 "spaces/14_Atlantic"）
     * @return 计算出的 space_size，若文件缺失或格式错误则返回 {@code null}
     */
    public static Integer parse(Path gameData, String mapName) {
        if (mapName == null || mapName.isBlank()) return null;

        var settingsFile = gameData.resolve(mapName).resolve("space.settings");
        if (!Files.exists(settingsFile)) return null;

        try (InputStream in = Files.newInputStream(settingsFile)) {
            Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(in);
            doc.getDocumentElement().normalize();

            Element bounds = findFirstElement(doc.getDocumentElement(), "bounds");
            if (bounds == null) return null;

            int minX = readInt(bounds, "minX");
            int maxX = readInt(bounds, "maxX");
            int minY = readInt(bounds, "minY");
            int maxY = readInt(bounds, "maxY");

            double chunkSize = 100.0;
            Element chunkSizeElem = findFirstElement(doc.getDocumentElement(), "chunkSize");
            if (chunkSizeElem != null) {
                String text = chunkSizeElem.getTextContent().trim();
                if (!text.isBlank()) {
                    try { chunkSize = Double.parseDouble(text); }
                    catch (NumberFormatException ignored) {}
                }
            }

            double chunksX = maxX - minX + 1;
            double chunksY = maxY - minY + 1;
            double spaceW = (chunksX - 4.0) * chunkSize;
            double spaceH = (chunksY - 4.0) * chunkSize;
            int spaceSize = (int) Math.round(Math.max(spaceW, spaceH));

            return spaceSize;
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归查找第一个匹配标签名的元素（支持属性或子元素）。 */
    private static Element findFirstElement(Element parent, String tagName) {
        // 先检查属性中的值匹配
        if (parent.hasAttribute(tagName)) return parent;

        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                if (child.getTagName().equals(tagName)) return child;
                Element found = findFirstElement(child, tagName);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 按标签名或属性名读取整数值。 */
    private static int readInt(Element parent, String name) {
        // 先尝试作为属性
        String attr = parent.getAttribute(name);
        if (!attr.isBlank()) {
            try { return Integer.parseInt(attr.trim()); }
            catch (NumberFormatException e) { return 0; }
        }
        // 再尝试作为子元素文本
        Element child = findFirstElement(parent, name);
        if (child != null) {
            try { return Integer.parseInt(child.getTextContent().trim()); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
