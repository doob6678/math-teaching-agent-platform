package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class TextbookRetrievalService {

    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final LocalTextbookBm25SearchEngine searchEngine;
    private final RetrievalAuditSink auditSink;
    private volatile CachedTextbookCorpus cachedCorpus;

    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink) {
        this.catalogReader = catalogReader;
        this.chunkReader = chunkReader;
        this.searchEngine = searchEngine;
        this.auditSink = auditSink;
    }

    public TextbookSearchResponse search(Path processedBooksRoot, TextbookSearchRequest request) {
        return search(processedBooksRoot, request, RetrievalRequestContext.defaultTextbookSearch());
    }

    public TextbookSearchResponse search(
            Path processedBooksRoot,
            TextbookSearchRequest request,
            RetrievalRequestContext requestContext) {
        String queryId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        List<TextbookChunk> chunks = loadCorpus(normalizedRoot).chunks();
        List<TextbookSearchHit> hits = searchEngine.search(request.query(), chunks, request.limit());
        int elapsedMs = elapsedMs(startedAtNanos);
        TextbookSearchResponse response = new TextbookSearchResponse(
                queryId,
                request.query(),
                request.limit(),
                "local_bm25_first",
                hits.size(),
                hits);
        auditSink.record(RetrievalAuditEvent.from(queryId, request, response, elapsedMs, requestContext));
        return response;
    }

    private CachedTextbookCorpus loadCorpus(Path processedBooksRoot) {
        List<TextbookCatalogItem> books = catalogReader.read(processedBooksRoot.resolve("catalog.jsonl"));
        List<Path> chunkPaths = books.stream()
                .map(book -> chunksPath(processedBooksRoot, book))
                .toList();
        List<SourceFileSignature> signatures = sourceSignatures(processedBooksRoot.resolve("catalog.jsonl"), chunkPaths);
        CachedTextbookCorpus snapshot = cachedCorpus;
        if (snapshot != null && snapshot.processedBooksRoot().equals(processedBooksRoot) && snapshot.signatures().equals(signatures)) {
            return snapshot;
        }

        List<TextbookChunk> chunks = new ArrayList<>();
        for (Path chunksPath : chunkPaths) {
            chunks.addAll(chunkReader.read(chunksPath));
        }
        CachedTextbookCorpus loaded = new CachedTextbookCorpus(processedBooksRoot, signatures, List.copyOf(chunks));
        cachedCorpus = loaded;
        return loaded;
    }

    private static List<SourceFileSignature> sourceSignatures(Path catalogPath, List<Path> chunkPaths) {
        List<SourceFileSignature> signatures = new ArrayList<>();
        signatures.add(sourceSignature(catalogPath));
        chunkPaths.stream().map(TextbookRetrievalService::sourceSignature).forEach(signatures::add);
        return List.copyOf(signatures);
    }

    private static SourceFileSignature sourceSignature(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return new SourceFileSignature(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint textbook source file: " + path, e);
        }
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

    private static int elapsedMs(long startedAtNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private record SourceFileSignature(Path path, long size, long lastModifiedNanos) {
    }

    private record CachedTextbookCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            List<TextbookChunk> chunks) {
    }
}
