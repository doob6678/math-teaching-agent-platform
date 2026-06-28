package com.doob.mathagent.teaching;

/**
 * 教学任务使用的证据。
 *
 * @param sourceScope 证据作用域，例如 PUBLIC_TEXTBOOK、PRIVATE_FEISHU、USER_HISTORY。
 * @param sourceTitle 证据来源标题。
 * @param chunkId 教材或文档 chunk ID。
 * @param pageNo 教材 PDF 页码；非教材来源可为 0。
 * @param snippet 证据文本片段。
 */
public record TeachingEvidence(
        String sourceScope,
        String sourceTitle,
        String chunkId,
        int pageNo,
        String snippet) {
}
