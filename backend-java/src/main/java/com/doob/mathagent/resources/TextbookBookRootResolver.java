package com.doob.mathagent.resources;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Resolves a catalog book directory inside the configured processed-books root.
 *
 * <p>Generated catalogs can retain an absolute {@code book_root} from the Windows indexing machine. That path is
 * not portable into a Linux container, while {@code doc_id} is the stable directory key used by every generated
 * corpus. Resolution therefore accepts a catalog path only when it points inside the active root and otherwise
 * falls back to {@code processedBooksRoot/docId}. Both branches remain traversal-safe.</p>
 */
public final class TextbookBookRootResolver {

    private TextbookBookRootResolver() {
    }

    /**
     * Resolves an existing book directory without trusting an absolute path from another host.
     *
     * @param processedBooksRoot deployment-specific corpus mount
     * @param book catalog record whose doc id identifies the portable directory
     * @return normalized existing path inside {@code processedBooksRoot}
     */
    public static Path resolve(Path processedBooksRoot, TextbookCatalogItem book) {
        if (processedBooksRoot == null || book == null) {
            throw new IllegalArgumentException("Processed-books root and catalog item are required");
        }
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        Path catalogCandidate = catalogCandidate(normalizedRoot, book.bookRoot());
        if (catalogCandidate != null && Files.isDirectory(catalogCandidate)) {
            return catalogCandidate;
        }

        String docId = requirePortableDocId(book.docId());
        Path portableCandidate = normalizedRoot.resolve(docId).normalize();
        if (!portableCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Textbook docId escapes processed_books root");
        }
        if (!Files.isDirectory(portableCandidate)) {
            throw new IllegalArgumentException("Textbook book directory not found for docId " + docId);
        }
        return portableCandidate;
    }

    /** Returns a catalog path only when it is syntactically valid and remains under the deployment root. */
    private static Path catalogCandidate(Path normalizedRoot, String bookRoot) {
        if (bookRoot == null || bookRoot.isBlank()) {
            return null;
        }
        try {
            Path declared = Path.of(bookRoot.strip());
            Path resolved = declared.isAbsolute()
                    ? declared.toAbsolutePath().normalize()
                    : normalizedRoot.resolve(declared).normalize();
            return resolved.startsWith(normalizedRoot) ? resolved : null;
        } catch (InvalidPathException exception) {
            // A Windows catalog path is not necessarily parseable on Linux; docId remains the portable fallback.
            return null;
        }
    }

    /** Requires one plain path segment so a catalog record cannot use docId to escape the mounted corpus. */
    private static String requirePortableDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("Textbook docId is required");
        }
        String normalized = docId.strip();
        Path segment;
        try {
            segment = Path.of(normalized);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Textbook docId is not a valid path segment", exception);
        }
        if (segment.isAbsolute()
                || segment.getNameCount() != 1
                || ".".equals(normalized)
                || "..".equals(normalized)
                || normalized.contains("/")
                || normalized.contains("\\")) {
            throw new IllegalArgumentException("Textbook docId must be one path segment");
        }
        return normalized;
    }
}
