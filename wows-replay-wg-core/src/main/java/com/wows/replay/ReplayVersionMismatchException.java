package com.wows.replay;

/**
 * 版本门禁失败：回放 build 与应用层预期 build 不匹配（对标 Rust
 * {@code ToolkitError::ReplayVersionMismatch}，replay-parser-call-chain.md §5.1）。
 *
 * <p>调用链文档要求「build 不匹配直接拒绝，不要硬解」——在进入解析管线前抛出，
 * 防止用错版本的游戏数据解码。</p>
 */
public class ReplayVersionMismatchException extends ReplayException {

    public ReplayVersionMismatchException(String message) {
        super(message);
    }
}
