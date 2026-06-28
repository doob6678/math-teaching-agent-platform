package com.doob.mathagent.retrieval;

/**
 * 教材检索请求参数。
 */
public record TextbookSearchRequest(
        /** 用户输入的检索词，可是教材术语、题干片段、公式关键词或章节名称。 */
        String query,
        /** 返回命中数量上限；后端会限制在 1 到 50 之间，避免单次请求过大。 */
        int limit) {

    public TextbookSearchRequest {
        query = query == null ? "" : query.strip();
        if (limit <= 0) {
            limit = 10;
        } else if (limit > 50) {
            limit = 50;
        }
    }
}
