package com.wows.replay.spec.types;

import java.util.regex.Pattern;

/**
 * 游戏版本，从 clientVersionFromExe 字符串解析（如 "15.4.0.12345").
 * 对标 Rust's {@code wows_core::version::Version}.
 */
public record Version(int major, int minor, int patch, int build) implements Comparable<Version> {

    /**
     * Parse from clientVersionFromExe format: "major,minor,patch,build".
     * 旧版回放可能缺少 build（视为 0）。
     */
    public static Version fromClientExe(String versionStr) {
        if (versionStr == null || versionStr.isBlank()) {
            return new Version(0, 0, 0, 0);
        }
        var parts = versionStr.split(",");
        int major = parts.length > 0 ? parsePart(parts[0]) : 0;
        int minor = parts.length > 1 ? parsePart(parts[1]) : 0;
        int patch = parts.length > 2 ? parsePart(parts[2]) : 0;
        int build = parts.length > 3 ? parsePart(parts[3]) : 0;
        return new Version(major, minor, patch, build);
    }

    /**
     * 从 Account.def 实体定义 XML 中提取游戏版本。
     *
     * <p>The file contains a node like
     * {@code <curVersion_15_1_0_11965230></curVersion_15_1_0_11965230>}
     * 其标签名编码了版本号。</p>
     */
    public static Version fromAccountDef(String xml) {
        var matcher = VERSION_NODE_PATTERN.matcher(xml);
        if (!matcher.find()) return new Version(0, 0, 0, 0);

        String rest = matcher.group(1).startsWith("Release_") || matcher.group(1).startsWith("release_")
            ? matcher.group(1).substring("Release_".length())
            : matcher.group(1);
        var parts = rest.split("_");
        if (parts.length >= 4) {
            return new Version(
                parsePart(parts[0]), parsePart(parts[1]),
                parsePart(parts[2]), parsePart(parts[parts.length - 1]));
        }
        return new Version(0, 0, 0, 0);
    }

    /** 匹配 {@code curVersion_X_Y_Z_B} or {@code curVersion_release_X_Y_Z_B}. */
    private static final Pattern VERSION_NODE_PATTERN = Pattern.compile(
        "curVersion_(?:[Rr]elease_)?(\\d+_\\d+_\\d+_\\d+(?:_\\d+)?)");

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 检查此版本是否至少为 {@code other}（忽略 build）。 */
    public boolean isAtLeast 比较(Version other) {
        if (major != other.major) return major > other.major;
        if (minor != other.minor) return minor > other.minor;
        return patch >= other.patch;
    }

    /** File-system path segment: {@code "major.minor.patch"}. */
    public String toPath 路径() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public int compareTo 比较(Version o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        return Integer.compare(build, o.build);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + "." + build;
    }
}
