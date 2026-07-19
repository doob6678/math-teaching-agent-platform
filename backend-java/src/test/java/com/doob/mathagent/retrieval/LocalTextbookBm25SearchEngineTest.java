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
    void exposesVisibleHeadingAsAnIndependentLexicalCandidateRoute() {
        TextbookChunk headingMatch = chunk(
                "book_a_p123_heading_001",
                "4.3.2 独立性检验",
                "本页正文使用图表说明分类变量的关系。",
                "pages/p123.png",
                123,
                "116");
        TextbookChunk bodyMatch = chunk(
                "book_a_p124_body_001",
                "4.3 统计模型",
                "独立性检验 独立性检验 独立性检验 的练习提示。",
                "pages/p124.png",
                124,
                "117");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.searchSectionTitles(
                "卡方与独立性检验", List.of(bodyMatch, headingMatch), 2);

        assertThat(hits)
                .extracting(TextbookSearchHit::chunkId)
                .containsExactly("book_a_p123_heading_001");
        assertThat(hits.getFirst().retrievalStrategy()).isEqualTo("local_title_bm25");
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

    @Test
    void keepsMidPageEvidenceInSnippetForSemanticRerank() {
        String prefix = "导数定义、函数图象、切线斜率、极限符号。".repeat(20);
        TextbookChunk target = chunk(
                "book_a_p130_text_001",
                "统计与概率",
                prefix + "我们已经知道,利用古典概型能够方便地确定出有关随机事件的概率,但是并不是所有试验都能归结为古典概型。",
                "pages/p130.png",
                130,
                "123");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.search("利用古典概型能够方便地确定出有关随机事件的概率", List.of(target), 1);

        assertThat(hits)
                .hasSize(1)
                .first()
                .satisfies(hit -> assertThat(hit.textSnippet())
                        .contains("利用古典概型能够方便地确定出有关随机事件的概率")
                        .doesNotStartWith(prefix.substring(0, 40)));
    }

    @Test
    void skipsOcrEmptyPlaceholderPageInTextRetrieval() {
        TextbookChunk emptyPage = chunk(
                "book_a_p080_text_001",
                "第六章导数及其应用",
                "# p080\n## 正文\n（本页文本层为空，需 OCR 或视觉模型补充。）",
                "pages/p080.png",
                80,
                "未识别");
        TextbookChunk contentPage = chunk(
                "book_a_p083_text_001",
                "第六章导数及其应用",
                "这就说明，导函数存在时，可以把某一点的导数看成导函数在该点的取值。",
                "pages/p083.png",
                83,
                "76");

        LocalTextbookBm25SearchEngine engine = new LocalTextbookBm25SearchEngine();

        List<TextbookSearchHit> hits = engine.search("第六章导数及其应用 这就说明", List.of(emptyPage, contentPage), 5);

        assertThat(hits)
                .extracting(TextbookSearchHit::chunkId)
                .containsExactly("book_a_p083_text_001");
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
                sourcePageImage,
                chunkId + "__section");
    }
}
