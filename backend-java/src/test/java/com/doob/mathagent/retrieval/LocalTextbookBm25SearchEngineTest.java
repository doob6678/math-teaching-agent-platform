package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.resources.TextbookChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalTextbookBm25SearchEngineTest {

    @Test
    void ranksExactTextbookTermBeforeGenericFunctionPage() {
        TextbookChunk target = chunk(
                "book_a_p101_text_001",
                "分段函数",
                "分段函数是在定义域的不同部分用不同解析式表示的函数。",
                "pages/p101.png");
        TextbookChunk generic = chunk(
                "book_a_p080_text_001",
                "函数概念",
                "函数 函数 函数 映射 对应关系 定义域 值域。",
                "pages/p080.png");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.search("分段函数的定义", List.of(generic, target), 5);

        assertThat(hits)
                .isNotEmpty()
                .first()
                .satisfies(hit -> {
                    assertThat(hit.chunkId()).isEqualTo("book_a_p101_text_001");
                    assertThat(hit.retrievalStrategy()).isEqualTo("local_bm25");
                    assertThat(hit.score()).isGreaterThan(0.0);
                    assertThat(hit.bookName()).isEqualTo("教材A");
                    assertThat(hit.chapterPath()).containsExactly("第三章 函数");
                    assertThat(hit.pageNo()).isEqualTo(101);
                    assertThat(hit.printedPageNo()).isEqualTo("98");
                    assertThat(hit.sectionTitle()).isEqualTo("分段函数");
                    assertThat(hit.textSnippet()).contains("分段函数");
                    assertThat(hit.sourcePageImage()).isEqualTo("pages/p101.png");
                });
    }

    @Test
    void returnsNoHitsForBlankQuery() {
        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        assertThat(engine.search("   ", List.of(chunk("c1", "集合", "集合的概念", "pages/p001.png")), 5))
                .isEmpty();
    }

    private static TextbookChunk chunk(String chunkId, String sectionTitle, String text, String sourcePageImage) {
        return new TextbookChunk(
                chunkId,
                "book_a",
                "教材A",
                "必修 第一册",
                List.of("第三章 函数"),
                sourcePageImage.contains("101") ? 101 : 80,
                sourcePageImage.contains("101") ? "98" : "77",
                "page_summary",
                sectionTitle,
                text,
                "",
                List.of(),
                sourcePageImage);
    }
}
