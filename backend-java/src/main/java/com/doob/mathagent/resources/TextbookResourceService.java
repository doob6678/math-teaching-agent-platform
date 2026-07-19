package com.doob.mathagent.resources;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TextbookResourceService {

    private final TextbookCatalogReader catalogReader;

    public TextbookResourceService(TextbookCatalogReader catalogReader) {
        this.catalogReader = catalogReader;
    }

    public TextbookResourceSummary summarize(Path processedBooksRoot) {
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        List<TextbookCatalogItem> books = catalogReader.read(normalizedRoot.resolve("catalog.jsonl"));
        int totalChunkCount = books.stream().mapToInt(TextbookCatalogItem::chunkCount).sum();
        int totalPageCount = books.stream().mapToInt(TextbookCatalogItem::pageCount).sum();
        return new TextbookResourceSummary(
                normalizedRoot,
                books.size(),
                totalChunkCount,
                totalPageCount,
                books);
    }
}
