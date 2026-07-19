package com.doob.mathagent.retrieval;

import java.util.Arrays;
import java.util.List;

/**
 * 教材检索请求参数。
 */
public record TextbookSearchRequest(
        /** 用户输入的检索词，可是教材术语、题干片段、公式关键词或章节名称。 */
        String query,
        /** Optional LaTex or plain-text formula that joins the topic query for BGE retrieval. */
        String formulaQuery,
        /** Optional formula image data URL forwarded only to the worker-owned CLIP page index. */
        String formulaImage,
        /** 返回命中数量上限；后端会限制在 1 到 50 之间，避免单次请求过大。 */
        int limit,
        /** Optional public textbook document ids selected by a caller, such as a chosen publisher/edition. */
        List<String> documentIds,
        /** Stable code of the user-selected retrieval strategy. */
        String retrievalMode) {

    public TextbookSearchRequest(String query, int limit) {
        this(query, "", "", limit, List.of(), TextbookRetrievalMode.HYBRID.code());
    }

    public TextbookSearchRequest(String query, int limit, List<String> documentIds) {
        this(query, "", "", limit, documentIds, TextbookRetrievalMode.HYBRID.code());
    }

    public TextbookSearchRequest {
        query = query == null ? "" : query.strip();
        formulaQuery = formulaQuery == null ? "" : formulaQuery.replaceAll("\\s+", " ").strip();
        formulaImage = formulaImage == null ? "" : formulaImage.strip();
        retrievalMode = TextbookRetrievalMode.from(retrievalMode).code();
        if (limit <= 0) {
            limit = 10;
        } else if (limit > 50) {
            limit = 50;
        }
        documentIds = documentIds == null ? List.of() : documentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    /** Ensures BGE coarse recall and BGE rerank receive exactly the same topic-plus-formula intent. */
    public String semanticQuery() {
        return String.join(" ", query, formulaQuery).strip();
    }

    public TextbookRetrievalMode mode() {
        return TextbookRetrievalMode.from(retrievalMode);
    }

    public boolean hasFormulaImage() {
        return !formulaImage.isBlank();
    }
}
