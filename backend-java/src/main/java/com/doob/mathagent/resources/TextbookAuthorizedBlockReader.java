package com.doob.mathagent.resources;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Resolves bounded parsed textbook chunks from the deployment-owned corpus.
 *
 * <p>The broker supplies only a persisted textbook docId. Catalog lookup and path resolution stay inside Java so
 * callers never receive a corpus path or a filesystem capability.</p>
 */
@Service
public class TextbookAuthorizedBlockReader {

    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final TextbookResourceProperties properties;

    @Autowired
    public TextbookAuthorizedBlockReader(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            Environment environment) {
        this(catalogReader, chunkReader, TextbookResourceProperties.fromSpringEnvironment(environment));
    }

    public TextbookAuthorizedBlockReader(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            TextbookResourceProperties properties) {
        this.catalogReader = catalogReader;
        this.chunkReader = chunkReader;
        this.properties = properties;
    }

    /** Returns whether the persisted docId maps to an active parsed textbook corpus. */
    public boolean isAvailable(String documentId) {
        try {
            return chunksPath(documentId) != null;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    /** Returns source-order parsed blocks for one already-authorized textbook. */
    public List<TeacherDocumentBlockResponse> read(String documentId) {
        Path chunks = chunksPath(documentId);
        if (chunks == null) {
            throw new IllegalArgumentException("Authorized textbook source is unavailable");
        }
        return chunkReader.read(chunks).stream()
                .filter(chunk -> documentId.equals(chunk.docId()))
                .map(chunk -> new TeacherDocumentBlockResponse(
                        safe(chunk.chunkId()), documentId, safe(chunk.chunkId()), "textbook_markdown",
                        Math.max(0, chunk.pageNo()), "", safe(chunk.sectionTitle()), chunk.pageNo() > 0 ? chunk.pageNo() : null,
                        safe(chunk.printedPageNo()), "", "reference", safe(chunk.text()), safe(chunk.text()),
                        "[]", "[]", "[]", "[]", "", 1.0d, "active"))
                .toList();
    }

    /** Returns the retrieval-authorized textbook page and its tightly bounded neighboring source pages. */
    public List<TeacherDocumentBlockResponse> readPageWindow(
            String documentId, int authorizedPageNo, int requestedPageNo, int pageRadius) {
        if (authorizedPageNo <= 0 || requestedPageNo != authorizedPageNo || pageRadius < 0 || pageRadius > 4) {
            throw new IllegalArgumentException("Requested textbook page is not authorized by the retrieval evidence");
        }
        return read(documentId).stream()
                .filter(block -> block.pageNo() != null && Math.abs(block.pageNo() - authorizedPageNo) <= pageRadius)
                .sorted(java.util.Comparator.comparingInt((TeacherDocumentBlockResponse block) ->
                        block.pageNo() == null ? Integer.MAX_VALUE : Math.abs(block.pageNo() - authorizedPageNo))
                        .thenComparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
    }

    private Path chunksPath(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return null;
        }
        Path root = properties.processedBooksRoot();
        TextbookCatalogItem book = catalogReader.read(root.resolve("catalog.jsonl")).stream()
                .filter(candidate -> documentId.equals(candidate.docId()))
                .findFirst()
                .orElse(null);
        if (book == null) {
            return null;
        }
        Path bookRoot = TextbookBookRootResolver.resolve(root, book);
        Path aiChunks = bookRoot.resolve("jsonl_ai").resolve("chunks.jsonl").normalize();
        Path textChunks = bookRoot.resolve("jsonl").resolve("chunks.jsonl").normalize();
        if (!aiChunks.startsWith(bookRoot) || !textChunks.startsWith(bookRoot)) {
            throw new IllegalArgumentException("Textbook chunks path escapes authorized book root");
        }
        if (Files.isRegularFile(aiChunks)) {
            return aiChunks;
        }
        return Files.isRegularFile(textChunks) ? textChunks : null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
