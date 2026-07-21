package com.doob.mathagent.retrieval;

import java.util.List;

public record TextbookSearchHit(
        String chunkId,
        String sectionId,
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
        List<String> imageRelPaths,
        String sourcePageImage,
        String pageQualityLabel,
        String pageImageUri) {

    /**
     * 服务层在拿到命中后再补受控图片地址，避免底层检索引擎直接耦合 HTTP 资源暴露规则。
     */
    public TextbookSearchHit(
            String chunkId,
            String sectionId,
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
            String sourcePageImage,
            String pageQualityLabel,
            String pageImageUri) {
        this(
                chunkId,
                sectionId,
                score,
                retrievalStrategy,
                docId,
                bookName,
                volume,
                chapterPath,
                pageNo,
                printedPageNo,
                sectionTitle,
                textSnippet,
                formulaText,
                List.of(),
                sourcePageImage,
                pageQualityLabel,
                pageImageUri);
    }

    public TextbookSearchHit withPageImageUri(String resolvedPageImageUri) {
        return new TextbookSearchHit(
                chunkId,
                sectionId,
                score,
                retrievalStrategy,
                docId,
                bookName,
                volume,
                chapterPath,
                pageNo,
                printedPageNo,
                sectionTitle,
                textSnippet,
                formulaText,
                imageRelPaths,
                sourcePageImage,
                pageQualityLabel,
                resolvedPageImageUri);
    }
}
