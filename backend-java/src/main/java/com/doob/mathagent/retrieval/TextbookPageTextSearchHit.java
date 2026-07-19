package com.doob.mathagent.retrieval;

/** Public page metadata returned by the worker-owned BGE textbook text index. */
public record TextbookPageTextSearchHit(
        double score,
        String chunkId,
        String sectionId,
        String sourceChunkId,
        String docId,
        String bookName,
        String chapterPath,
        int pageNo,
        String printedPageNo,
        String sectionTitle,
        String text,
        String imageUri) {
}
