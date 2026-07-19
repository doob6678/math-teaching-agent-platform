package com.doob.mathagent.resources;

import java.nio.file.Path;
import java.util.List;

public record TextbookResourceSummary(
        Path processedBooksRoot,
        int bookCount,
        int totalChunkCount,
        int totalPageCount,
        List<TextbookCatalogItem> books) {
}
