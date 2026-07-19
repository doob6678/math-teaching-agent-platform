package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.resources.TextbookChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the route-neutral candidate policies used before textbook reranking.
 * These cases intentionally use synthetic document names so they cannot encode
 * any benchmark query, textbook title, page number, or expected answer.
 */
class TextbookRetrievalCandidatePolicyTest {

    @Test
    void semanticOnlyDocumentSharesTheBoundedWindowWithLexicalDocuments() {
        List<String> selected = TextbookRetrievalService.interleaveDocumentIds(
                List.of("lexical-a", "lexical-b", "lexical-c"),
                List.of("semantic-a", "semantic-b"),
                3);

        assertThat(selected).containsExactly("lexical-a", "semantic-a", "lexical-b");
    }

    @Test
    void duplicateDocumentFromBothRoutesUsesOneSlot() {
        List<String> selected = TextbookRetrievalService.interleaveDocumentIds(
                List.of("shared", "lexical-b"),
                List.of("shared", "semantic-b"),
                3);

        assertThat(selected).containsExactly("shared", "lexical-b", "semantic-b");
    }

    @Test
    void titleRouteSharesTheSameBoundedDocumentWindowWithoutScoreFusion() {
        List<String> selected = TextbookRetrievalService.interleaveDocumentIds(
                List.of(
                        List.of("body-a", "body-b"),
                        List.of("title-a", "title-b"),
                        List.of("semantic-a", "semantic-b")),
                3);

        assertThat(selected).containsExactly("body-a", "title-a", "semantic-a");
    }

    @Test
    void sectionExpansionKeepsOnlySiblingBlocksFromTheWorkerHitPage() {
        List<TextbookChunk> chunks = List.of(
                chunk("page-one-heading", 101),
                chunk("page-one-prose", 101),
                chunk("page-two-continuation", 102));

        List<TextbookChunk> selected = TextbookRetrievalService.samePageSectionChunks(chunks, 101);

        assertThat(selected).extracting(TextbookChunk::chunkId)
                .containsExactlyInAnyOrder("page-one-heading", "page-one-prose");
    }

    private static TextbookChunk chunk(String chunkId, int pageNo) {
        return new TextbookChunk(
                chunkId,
                "book",
                "教材",
                "必修",
                List.of("章节", "小标题"),
                pageNo,
                String.valueOf(pageNo),
                "section_prose",
                "小标题",
                "正文",
                "",
                List.of(),
                "pages/p" + pageNo + ".png",
                "book__section");
    }
}
