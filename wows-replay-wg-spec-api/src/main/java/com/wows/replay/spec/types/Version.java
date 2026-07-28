package com.wows.replay.spec.types;

/**
 * Game version parsed from clientVersionFromExe string (e.g. "15.4.0.12345").
 */
public record Version(int major, int minor, int patch, int build) implements Comparable<Version> {

    /**
     * Parse from clientVersionFromExe format: "major,minor,patch,build".
     * Build may be absent in older replays (treated as 0).
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

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public int compareTo(Version o) {
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
