package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.ReplayVersionMismatchException;

/**
 * Configuration for {@link ReplayAnalyzer}.
 *
 * <p>只保留实际生效的选项：JSON 输出格式与版本门禁。</p>
 */
public record ReplayAnalyzerConfig(
    /** Pretty-print the JSON output */
    boolean prettyPrint,

    /** 版本门禁（§12.4.1）：期望的 build（clientVersionFromExe 第 4 段），非空时校验。
     *  不匹配抛 {@link ReplayVersionMismatchException}。 */
    String expectedBuild
) {
    public static final ReplayAnalyzerConfig DEFAULT = new ReplayAnalyzerConfig(false, null);

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean prettyPrint;
        private String expectedBuild;

        public Builder prettyPrint(boolean enabled) { prettyPrint = enabled; return this; }
        /** 版本门禁：期望 build（clientVersionFromExe 第 4 段）。 */
        public Builder expectedBuild(String build) { expectedBuild = build; return this; }

        public ReplayAnalyzerConfig build() {
            return new ReplayAnalyzerConfig(prettyPrint, expectedBuild);
        }
    }
}
