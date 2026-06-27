package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TextbookRetrievalService {

    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final LocalTextbookBm25SearchEngine searchEngine;

    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine) {
        this.catalogReader = catalogReader;
        this.chunkReader = chunkReader;
        this.searchEngine = searchEngine;
    }

    public TextbookSearchResponse search(Path processedBooksRoot, TextbookSearchRequest request) {
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        List<TextbookChunk> chunks = loadChunks(normalizedRoot);
        List<TextbookSearchHit> hits = searchEngine.search(request.query(), chunks, request.limit());
        return new TextbookSearchResponse(
                request.query(),
                request.limit(),
                "local_bm25_first",
                hits.size(),
                hits);
    }

    private List<TextbookChunk> loadChunks(Path processedBooksRoot) {
        List<TextbookCatalogItem> books = catalogReader.read(processedBooksRoot.resolve("catalog.jsonl"));
        List<TextbookChunk> chunks = new ArrayList<>();
        for (TextbookCatalogItem book : books) {
            Path chunksPath = chunksPath(processedBooksRoot, book);
            chunks.addAll(chunkReader.read(chunksPath));
        }
        return List.copyOf(chunks);
    }

    private static Path chunksPath(Path processedBooksRoot, TextbookCatalogItem book) {
        Path bookRoot = Paths.get(book.bookRoot());
        if (!bookRoot.isAbsolute()) {
            bookRoot = processedBooksRoot.resolve(bookRoot);
        }
        Path aiChunks = bookRoot.resolve("jsonl_ai/chunks.jsonl");
        if (Files.exists(aiChunks)) {
            return aiChunks;
        }
        Path textChunks = bookRoot.resolve("jsonl/chunks.jsonl");
        if (Files.exists(textChunks)) {
            return textChunks;
        }
        throw new IllegalStateException("Missing textbook chunks for book " + book.docId() + ": " + bookRoot);
    }
}
