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

    @Test
    void downranksCoverBackmatterBelowContentPage() {
        TextbookChunk cover = chunk(
                "book_a_p190_text_001",
                "00.00 元",
                "SHUXUE PUTONG GAOZHONG JIAOKESHU 定价:00.00 元 绿色印刷产品 数学 数学",
                "pages/p190.png",
                190,
                "未识别");
        TextbookChunk content = chunk(
                "book_a_p120_text_001",
                "数学建模",
                "数学建模是用数学语言表达现实问题并求解的过程。",
                "pages/p120.png",
                120,
                "113");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.search("数学", List.of(cover, content), 2);

        assertThat(hits)
                .extracting(TextbookSearchHit::chunkId)
                .containsExactly("book_a_p120_text_001", "book_a_p190_text_001");
        assertThat(hits.getFirst().pageQualityLabel()).isEqualTo("content_page");
        assertThat(hits.get(1).pageQualityLabel()).isEqualTo("cover_or_backmatter");
    }

    @Test
    void labelsNumericAppendixPage() {
        TextbookChunk numericAppendix = chunk(
                "book_a_p095_text_001",
                "附录",
                "附录 69667878457289676371617477856376856759647079767962837165748198907389808978826978627570766766736180698458809078696881677968767471728580788797627269757767668380797587807674",
                "pages/p095.png",
                95,
                "88");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.search("附录", List.of(numericAppendix), 1);

        assertThat(hits)
                .hasSize(1)
                .first()
                .extracting(TextbookSearchHit::pageQualityLabel)
                .isEqualTo("numeric_appendix");
    }

    private static TextbookChunk chunk(String chunkId, String sectionTitle, String text, String sourcePageImage) {
        return chunk(
                chunkId,
                sectionTitle,
                text,
                sourcePageImage,
                sourcePageImage.contains("101") ? 101 : 80,
                sourcePageImage.contains("101") ? "98" : "77");
    }

    private static TextbookChunk chunk(
            String chunkId,
            String sectionTitle,
            String text,
            String sourcePageImage,
            int pageNo,
            String printedPageNo) {
        return new TextbookChunk(
                chunkId,
                "book_a",
                "教材A",
                "必修 第一册",
                List.of("第三章 函数"),
                pageNo,
                printedPageNo,
                "page_summary",
                sectionTitle,
                text,
                "",
                List.of(),
                sourcePageImage);
    }
}
