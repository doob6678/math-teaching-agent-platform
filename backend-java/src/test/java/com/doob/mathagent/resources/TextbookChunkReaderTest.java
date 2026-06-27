package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookChunkReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsChunkJsonlWithTextbookMetadata() throws Exception {
        Path chunks = tempDir.resolve("chunks.jsonl");
        Files.writeString(chunks, """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数是在定义域的不同部分用不同解析式表示的函数。","formula_text":"f(x)=x","image_rel_paths":["images/p101_fig001.png"],"source_pdf":"source/book.pdf","source_page_image":"pages/p101.png","bbox":null,"ocr_engine":"pymupdf_text_layer","ocr_confidence":null,"extra_field":"future"}
                """);

        TextbookChunkReader reader = new TextbookChunkReader();

        assertThat(reader.read(chunks))
                .hasSize(1)
                .first()
                .satisfies(chunk -> {
                    assertThat(chunk.chunkId()).isEqualTo("book_a_p101_text_001");
                    assertThat(chunk.docId()).isEqualTo("book_a");
                    assertThat(chunk.bookName()).isEqualTo("教材A");
                    assertThat(chunk.chapterPath()).containsExactly("第三章 函数");
                    assertThat(chunk.pageNo()).isEqualTo(101);
                    assertThat(chunk.printedPageNo()).isEqualTo("98");
                    assertThat(chunk.sectionTitle()).isEqualTo("分段函数");
                    assertThat(chunk.text()).contains("分段函数");
                    assertThat(chunk.formulaText()).isEqualTo("f(x)=x");
                    assertThat(chunk.imageRelPaths()).containsExactly("images/p101_fig001.png");
                    assertThat(chunk.sourcePageImage()).isEqualTo("pages/p101.png");
                });
    }

    @Test
    void ignoresBlankLinesInChunkJsonl() throws Exception {
        Path chunks = tempDir.resolve("chunks.jsonl");
        Files.writeString(chunks, "\n{\"chunk_id\":\"c1\",\"doc_id\":\"book_a\",\"book_name\":\"教材A\",\"volume\":\"必修\",\"chapter_path\":[],\"page_no\":1,\"printed_page_no\":\"\",\"chunk_type\":\"page_summary\",\"section_title\":\"\",\"text\":\"集合\",\"formula_text\":\"\",\"image_rel_paths\":[],\"source_page_image\":\"pages/p001.png\"}\n\n");

        TextbookChunkReader reader = new TextbookChunkReader();

        assertThat(reader.read(chunks))
                .hasSize(1)
                .first()
                .extracting(TextbookChunk::chunkId)
                .isEqualTo("c1");
    }
}
