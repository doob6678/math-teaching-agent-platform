package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/** Opens only manifest-bound public canonical question assets for the PDF renderer. */
@Service
public class CanonicalMathPaperAssetService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.UUID URL_NAMESPACE = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private final Path corpusRoot;

    private static java.util.UUID uuid5(String value) {
        try {
            byte[] namespace = java.nio.ByteBuffer.allocate(16)
                    .putLong(URL_NAMESPACE.getMostSignificantBits()).putLong(URL_NAMESPACE.getLeastSignificantBits()).array();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(namespace);
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new java.util.UUID(java.nio.ByteBuffer.wrap(hash, 0, 8).getLong(), java.nio.ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (Exception ignored) {
            return new java.util.UUID(0L, 0L);
        }
    }


    public CanonicalMathPaperAssetService(
            @Value("${math-agent.teaching.canonical-paper.corpus-root:/app/data/math-paper-corpus}") Path corpusRoot) {
        this.corpusRoot = Objects.requireNonNull(corpusRoot).toAbsolutePath().normalize();
    }

    /**
     * Opens one question Markdown figure after rechecking the same manifest binding used by canonical deep reads.
     * The logical path must be a question-level figures/ entry; page images and asset-id-only lookups are rejected.
     */
    public Optional<VisibleAsset> openVisibleQuestionFigure(
            String documentRef, String questionNumber, String logicalPath, RequestSubject subject) {
        if (documentRef == null || documentRef.isBlank() || questionNumber == null
                || !questionNumber.matches("[1-9]\\d{0,2}") || logicalPath == null
                || !logicalPath.matches("figures/[A-Za-z0-9._/-]+") || logicalPath.contains("..")
                || subject == null || !Files.isDirectory(corpusRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> roots = Files.list(corpusRoot)) {
            return roots.filter(Files::isDirectory)
                    .map(root -> findQuestionFigure(root, documentRef, questionNumber, logicalPath))
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<VisibleAsset> findQuestionFigure(
            Path root, String documentRef, String questionNumber, String logicalPath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path manifest = normalizedRoot.resolve("source-manifest.json").normalize();
        if (!manifest.startsWith(normalizedRoot) || !Files.isRegularFile(manifest)) return Optional.empty();
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest));
            String name = source.path("documentFullName").asText("");
            String sourceHash = source.path("sourceSha256").asText("");
            if (!name.equals(normalizedRoot.getFileName().toString()) || !sourceHash.matches("[0-9a-fA-F]{64}")
                    || !documentRef.equals(uuid5(name + "\n" + sourceHash).toString())) {
                return Optional.empty();
            }
            for (JsonNode question : source.path("questions")) {
                if (!questionNumber.equals(question.path("questionNumber").asText(""))) continue;
                for (JsonNode asset : question.path("assets")) {
                    if (!logicalPath.equals(asset.path("canonicalAssetPath").asText(""))) continue;
                    return verifyAsset(normalizedRoot, asset.path("assetId").asText(""), asset);
                }
                return Optional.empty();
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Resolves an opaque canonical asset id after confirming the authenticated subject and published manifest. */
    public Optional<VisibleAsset> openVisibleAsset(String assetId, RequestSubject subject) {
        if (assetId == null || assetId.isBlank() || subject == null || !Files.isDirectory(corpusRoot)) return Optional.empty();
        String requested = assetId.strip();
        try (Stream<Path> roots = Files.list(corpusRoot)) {
            return roots.filter(Files::isDirectory)
                    .map(root -> findInPublishedRoot(root, requested))
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /** Resolves all manifest-bound assets for an already authorized transparent question reference. */
    public java.util.List<String> authorizedQuestionAssetIds(String documentRef, String questionNumber, RequestSubject subject) {
        if (documentRef == null || documentRef.isBlank() || questionNumber == null || questionNumber.isBlank() || subject == null) {
            return java.util.List.of();
        }
        try (Stream<Path> roots = Files.list(corpusRoot)) {
            return roots.filter(Files::isDirectory).flatMap(root -> {
                Path manifest = root.resolve("source-manifest.json").normalize();
                try {
                    if (!Files.isRegularFile(manifest)) return Stream.empty();
                    JsonNode source = JSON.readTree(Files.readString(manifest));
                    String expected = source.path("documentFullName").asText("");
                    String hash = source.path("sourceSha256").asText("");
                    if (!expected.equals(root.getFileName().toString()) || !hash.matches("[0-9a-fA-F]{64}")) return Stream.empty();
                    String derived = uuid5(expected + "\n" + hash).toString();
                    if (!documentRef.equals(derived)) return Stream.empty();
                    for (JsonNode question : source.path("questions")) {
                        if (!questionNumber.equals(question.path("questionNumber").asText(""))) continue;
                        java.util.List<String> ids = new java.util.ArrayList<>();
                        for (JsonNode value : question.path("assetIds")) if (!value.asText("").isBlank()) ids.add(value.asText(""));
                        for (JsonNode asset : question.path("assets")) if (!asset.path("assetId").asText("").isBlank()) ids.add(asset.path("assetId").asText(""));
                        return ids.stream().distinct().filter(id -> openVisibleAsset(id, subject).isPresent());
                    }
                } catch (Exception ignored) { }
                return Stream.empty();
            }).distinct().toList();
        } catch (IOException ignored) {
            return java.util.List.of();
        }
    }
    private Optional<VisibleAsset> findInPublishedRoot(Path root, String requested) {
        Path manifest = root.resolve("source-manifest.json").normalize();
        if (!manifest.startsWith(root) || !Files.isRegularFile(manifest)) return Optional.empty();
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest));
            String documentName = source.path("documentFullName").asText("");
            String sourceHash = source.path("sourceSha256").asText("");
            if (documentName.isBlank() || !documentName.equals(root.getFileName().toString())
                    || !sourceHash.matches("[0-9a-fA-F]{64}")) return Optional.empty();
            for (JsonNode page : source.path("pages")) {
                if (!requested.equals(page.path("assetId").asText(""))) continue;
                Optional<VisibleAsset> found = verifyAsset(root, requested, page);
                if (found.isPresent()) return found;
            }
            for (JsonNode question : source.path("questions")) {
                for (JsonNode asset : question.path("assets")) {
                    Optional<VisibleAsset> found = verifyAsset(root, requested, asset);
                    if (found.isPresent()) return found;
                }
                for (JsonNode value : question.path("assetIds")) {
                    if (requested.equals(value.asText(""))) {
                        Optional<VisibleAsset> found = verifyAsset(root, requested,
                                JSON.createObjectNode().put("assetId", requested));
                        if (found.isPresent()) return found;
                    }
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<VisibleAsset> verifyAsset(Path root, String requested, JsonNode asset) {
        if (!requested.equals(asset.path("assetId").asText(""))) return Optional.empty();
        String relative = asset.path("canonicalAssetPath").asText("");
        if (relative.isBlank()) return Optional.empty();
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return Optional.empty();
        String expected = asset.path("assetSha256").asText("");
        if (expected.matches("[0-9a-fA-F]{64}")) {
            try {
                if (!expected.equalsIgnoreCase(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))))) {
                    return Optional.empty();
                }
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        String mime = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg")
                || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpeg") ? "image/jpeg" : "image/png";
        return Optional.of(new VisibleAsset(requested, mime, new FileSystemResource(path)));
    }

    public record VisibleAsset(String assetId, String mimeType, FileSystemResource resource) {}
}
