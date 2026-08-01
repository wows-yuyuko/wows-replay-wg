package com.wows.replay.dumper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析 {@code {game_data}/{map_name}/space.settings} 并计算 {@code space_size}
 * （对标 Rust {@code replay-dumper::position::parse_space_settings}）。
 *
 * <pre>
 * chunks_x = maxX - minX + 1
 * space_w  = (chunks_x - 4) * chunk_size
 * space_size = max(space_w, space_h)
 * </pre>
 */
public final class SpaceSettings {

    private SpaceSettings() {}

    public static int parse(Path gameData, String mapName) {
        if (mapName == null) return 0;
        Path file = gameData.resolve(mapName).resolve("space.settings");
        if (!Files.exists(file)) return 0;
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(file.toFile());

            var bounds = doc.getElementsByTagName("bounds");
            if (bounds.getLength() == 0) return 0;
            var b = bounds.item(0);
            int minX = intAttr(b, "minX");
            int maxX = intAttr(b, "maxX");
            int minY = intAttr(b, "minY");
            int maxY = intAttr(b, "maxY");

            double chunkSize = 100.0;
            var chunkNodes = doc.getElementsByTagName("chunkSize");
            if (chunkNodes.getLength() > 0) {
                try {
                    chunkSize = Double.parseDouble(chunkNodes.item(0).getTextContent().trim());
                } catch (NumberFormatException ignored) {}
            }

            double chunksX = maxX - minX + 1;
            double chunksY = maxY - minY + 1;
            int spaceW = (int) Math.round((chunksX - 4.0) * chunkSize);
            int spaceH = (int) Math.round((chunksY - 4.0) * chunkSize);
            return Math.max(spaceW, spaceH);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int intAttr(org.w3c.dom.Node node, String name) {
        var attr = node.getAttributes();
        if (attr != null && attr.getNamedItem(name) != null) {
            try { return Integer.parseInt(attr.getNamedItem(name).getNodeValue()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
