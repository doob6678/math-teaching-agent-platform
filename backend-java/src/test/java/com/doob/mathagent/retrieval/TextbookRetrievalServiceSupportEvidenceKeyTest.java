package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextbookRetrievalServiceSupportEvidenceKeyTest {

    @Test
    void keepsCrossPageSectionEvidenceWhileCollapsingSamePageSiblingBlocks() {
        // A section identity can span pages. The final reranker needs both
        // pages, while heading/prose/caption siblings on one page are one unit.
        TextbookSearchHit pageOneHeading = hit("heading", 113);
        TextbookSearchHit pageOneProse = hit("prose", 113);
        TextbookSearchHit continuationPage = hit("continuation", 114);

        assertThat(TextbookRetrievalService.supportEvidenceKey(pageOneHeading))
                .isEqualTo(TextbookRetrievalService.supportEvidenceKey(pageOneProse));
        assertThat(TextbookRetrievalService.supportEvidenceKey(pageOneHeading))
                .isNotEqualTo(TextbookRetrievalService.supportEvidenceKey(continuationPage));
    }

    private static TextbookSearchHit hit(String chunkId, int pageNo) {
        return new TextbookSearchHit(
                chunkId,
                "book__section_refraction",
                0.0,
                "local_bm25",
                "book",
                "教材",
                "选择性必修",
                List.of("第六章", "利用导数推导折射定律"),
                pageNo,
                String.valueOf(pageNo),
                "利用导数推导折射定律",
                "正文",
                "",
                "pages/p" + pageNo + ".png",
                "content_page",
                "");
    }
}
