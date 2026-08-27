package com.gitlab.pipeline.viewer.services;

/**
 * Job 日志增量拉取结果（配合 Range 请求头）。
 * <p>
 * - content：本次返回的文本；full=true 时是完整日志（应整体替换），false 时是增量（应追加）；
 * - nextOffset / carry：下一次增量请求的输入 —— 字节偏移，以及上一块末尾未完成的多字节
 *   UTF-8 序列字节（Range 分块可能切断多字节字符，需拼接后再解码）。
 */
public final class JobTraceResult {

    public final String content;
    public final long nextOffset;
    public final byte[] carry;
    public final boolean full;

    public JobTraceResult(String content, long nextOffset, byte[] carry, boolean full) {
        this.content = content;
        this.nextOffset = nextOffset;
        this.carry = carry;
        this.full = full;
    }
}
