package com.doob.mathagent.resources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Resolves public textbook page images through backend-owned paths only.
 *
 * <p>Do not expose processed_books absolute paths to clients. Search hits and teaching flows should refer to textbook
 * page images through {@code /api/resources/textbooks/{docId}/pages/{pageNo}/image}, while this service maps the
 * stable doc/page key back to the local processed_books asset under a traversal-safe root.</p>
 */
@Service
public class TextbookPageImageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TextbookCatalogReader catalogReader;
    private volatile CachedPageImageIndex cachedIndex;

    public TextbookPageImageService(TextbookCatalogReader catalogReader) {
        this.catalogReader = catalogReader;
    }

    /**
     * Streams one processed textbook page image through a backend resource handle.
     */
    public VisibleTextbookPageImage openPageImage(Path processedBooksRoot, String docId, int pageNo) {
        CachedPageImageIndex index = loadIndex(processedBooksRoot);
        String normalizedDocId = requireText(docId, "docId is required");
        if (pageNo <= 0) {
            throw new IllegalArgumentException("pageNo must be positive");
        }
        PageImageMetadata metadata = index.pageByDocAndPage().get(pageKey(normalizedDocId, pageNo));
        if (metadata == null) {
            throw new IllegalArgumentException("Textbook page image not found");
        }
        TextbookCatalogItem catalogItem = index.catalogByDocId().get(normalizedDocId);
        if (catalogItem == null) {
            throw new IllegalArgumentException("Textbook catalog item not found");
        }
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        Path bookRoot = normalizeBookRoot(normalizedRoot, catalogItem.bookRoot());
        Path imagePath = resolveImagePath(normalizedRoot, bookRoot, metadata);
        if (!Files.isRegularFile(imagePath)) {
            throw new IllegalArgumentException("Textbook page image file is unavailable");
        }
        return new VisibleTextbookPageImage(
                normalizedDocId,
                pageNo,
                safeImageUri(normalizedDocId, pageNo),
                safeContentType(imagePath),
                safeFileName(normalizedDocId, pageNo, imagePath),
                new FileSystemResource(imagePath));
    }

    /**
     * Builds a stable backend URL for one processed textbook page image.
     */
    public String pageImageUri(String docId, int pageNo) {
        return safeImageUri(requireText(docId, "docId is required"), pageNo);
    }

    private CachedPageImageIndex loadIndex(Path processedBooksRoot) {
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        Path manifestPath = normalizedRoot.resolve("_page_image_index/manifest.json");
        Path metadataPath = normalizedRoot.resolve("_page_image_index/metadata.jsonl");
        String fingerprint = readFingerprint(manifestPath);
        CachedPageImageIndex cache = cachedIndex;
        if (cache != null
                && cache.processedBooksRoot().equals(normalizedRoot)
                && cache.fingerprint().equals(fingerprint)) {
            return cache;
        }
        List<TextbookCatalogItem> catalogItems = catalogReader.read(normalizedRoot.resolve("catalog.jsonl"));
        Map<String, TextbookCatalogItem> catalogByDocId = new HashMap<>();
        for (TextbookCatalogItem item : catalogItems) {
            catalogByDocId.put(item.docId(), item);
        }
        Map<String, PageImageMetadata> pageByDocAndPage = new HashMap<>();
        try {
            for (String line : Files.readAllLines(metadataPath, StandardCharsets.UTF_8)) {
                String normalizedLine = stripUtf8Bom(line);
                if (normalizedLine.isBlank()) {
                    continue;
                }
                PageImageMetadata metadata = OBJECT_MAPPER.readValue(normalizedLine, PageImageMetadata.class);
                if (!text(metadata.docId()).isBlank() && metadata.pageNo() > 0) {
                    pageByDocAndPage.put(pageKey(metadata.docId(), metadata.pageNo()), metadata);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read textbook page image metadata: " + metadataPath, exception);
        }
        CachedPageImageIndex loaded = new CachedPageImageIndex(
                normalizedRoot,
                fingerprint,
                Map.copyOf(catalogByDocId),
                Map.copyOf(pageByDocAndPage));
        cachedIndex = loaded;
        return loaded;
    }

    private static String readFingerprint(Path manifestPath) {
        try {
            return OBJECT_MAPPER.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8))
                    .path("fingerprint")
                    .asText("");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read textbook page image manifest: " + manifestPath, exception);
        }
    }

    private static Path normalizeBookRoot(Path processedBooksRoot, String bookRoot) {
        Path candidate = Path.of(requireText(bookRoot, "bookRoot is required"));
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : processedBooksRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(processedBooksRoot)) {
            throw new IllegalArgumentException("Textbook bookRoot escapes processed_books root");
        }
        return resolved;
    }

    private static Path resolveImagePath(Path processedBooksRoot, Path bookRoot, PageImageMetadata metadata) {
        String relPath = text(metadata.sourcePageImage());
        if (!relPath.isBlank()) {
            Path resolved = bookRoot.resolve(relPath).normalize();
            if (resolved.startsWith(processedBooksRoot)) {
                return resolved;
            }
        }
        String absoluteHint = text(metadata.pageImagePath());
        if (!absoluteHint.isBlank()) {
            Path resolved = Path.of(absoluteHint).toAbsolutePath().normalize();
            if (resolved.startsWith(processedBooksRoot)) {
                return resolved;
            }
        }
        throw new IllegalArgumentException("Textbook page image path is invalid");
    }

    private static String safeImageUri(String docId, int pageNo) {
        if (pageNo <= 0) {
            throw new IllegalArgumentException("pageNo must be positive");
        }
        return "/api/resources/textbooks/" + docId + "/pages/" + pageNo + "/image";
    }

    private static String safeContentType(Path imagePath) {
        try {
            String detected = Files.probeContentType(imagePath);
            return detected == null || detected.isBlank() ? "application/octet-stream" : detected;
        } catch (IOException exception) {
            return "application/octet-stream";
        }
    }

    private static String safeFileName(String docId, int pageNo, Path imagePath) {
        String fileName = imagePath.getFileName() == null ? "" : imagePath.getFileName().toString();
        if (fileName.isBlank()) {
            return docId + "-page-" + pageNo + ".bin";
        }
        return fileName;
    }

    private static String pageKey(String docId, int pageNo) {
        return docId.strip() + "#" + pageNo;
    }

    private static String stripUtf8Bom(String line) {
        return line != null && line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    public record VisibleTextbookPageImage(
            String docId,
            int pageNo,
            String imageUri,
            String mimeType,
            String fileName,
            Resource resource) {
    }

    private record CachedPageImageIndex(
            Path processedBooksRoot,
            String fingerprint,
            Map<String, TextbookCatalogItem> catalogByDocId,
            Map<String, PageImageMetadata> pageByDocAndPage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PageImageMetadata(
            @JsonProperty("doc_id") String docId,
            @JsonProperty("page_no") int pageNo,
            @JsonProperty("source_page_image") String sourcePageImage,
            @JsonProperty("page_image_path") String pageImagePath) {
    }
}
