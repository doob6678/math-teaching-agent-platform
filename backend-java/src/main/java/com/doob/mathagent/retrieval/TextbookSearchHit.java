package com.doob.mathagent.retrieval;

import java.util.List;

public record TextbookSearchHit(
        String chunkId,
        double score,
        String retrievalStrategy,
        String docId,
        String bookName,
        String volume,
        List<String> chapterPath,
        int pageNo,
        String printedPageNo,
        String sectionTitle,
        String textSnippet,
        String formulaText,
        String sourcePageImage) {
}
