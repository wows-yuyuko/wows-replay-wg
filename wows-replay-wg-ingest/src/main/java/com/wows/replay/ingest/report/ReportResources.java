package com.wows.replay.ingest.report;

import java.util.Optional;

/**
 * 报告装配所需的游戏资源查询器（对标 Rust {@code ResourceLoader}，report.rs §2.5）。
 *
 * <p>可选注入。缺少时回退到原始 ID 字符串（§5.7 的 fallback 语义）。</p>
 */
public interface ReportResources {

    /** 本地化显示名；unknown ID → empty（调用方回退到原始 ID）。 */
    default Optional<String> localizedName(String id) {
        return Optional.empty();
    }

    /** 空实现——全部回退。 */
    static ReportResources empty() {
        return new ReportResources() {};
    }
}
