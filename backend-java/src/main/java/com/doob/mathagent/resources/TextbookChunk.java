package com.doob.mathagent.resources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TextbookChunk(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("book_name") String bookName,
        String volume,
        @JsonProperty("chapter_path") List<String> chapterPath,
        @JsonProperty("page_no") int pageNo,
        @JsonProperty("printed_page_no") String printedPageNo,
        @JsonProperty("chunk_type") String chunkType,
        @JsonProperty("section_title") String sectionTitle,
        String text,
        @JsonProperty("formula_text") String formulaText,
        @JsonProperty("image_rel_paths") List<String> imageRelPaths,
        @JsonProperty("source_page_image") String sourcePageImage) {
}
