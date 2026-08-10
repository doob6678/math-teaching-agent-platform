package com.doob.mathagent.resources;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TextbookCatalogItem(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("book_name") String bookName,
        String volume,
        @JsonProperty("book_root") String bookRoot,
        String manifest,
        @JsonProperty("chunk_count")
        @JsonAlias("section_count") int chunkCount,
        @JsonProperty("page_count")
        @JsonAlias("source_page_rows") int pageCount,
        @JsonProperty("ai_ok") boolean aiOk) {
}
