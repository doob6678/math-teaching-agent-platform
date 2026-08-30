package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the question-figure gateway used by canonical handout image materialization: only a manifest-bound
 * figures/ row of the authorized question resolves, while page images and cross-question reuse fail closed.
 */
class CanonicalMathPaperAssetServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void opensOnlyTheManifestBoundFigureOfTheAuthorizedQuestion() throws Exception {
        String documentName = "2024立体几何真题.pdf";
        String sourceHash = sha256("original-solid-geometry-pdf".getBytes(StandardCharsets.UTF_8));
        Path paperRoot = tempDir.resolve(documentName);
        Files.createDirectories(paperRoot.resolve("questions"));
        Files.createDirectories(paperRoot.resolve("figures"));
        Path document = paperRoot.resolve("document.md");
        Path question = paperRoot.resolve("questions/q-001.md");
        Path figure = paperRoot.resolve("figures/q-001-01.png");
        Files.writeString(document, "# 2024立体几何真题\n", StandardCharsets.UTF_8);
        Files.writeString(question, "![第 1 题图](figures/q-001-01.png)\n", StandardCharsets.UTF_8);
        Files.write(figure, "figure-bytes".getBytes(StandardCharsets.UTF_8));
        Files.writeString(paperRoot.resolve("source-manifest.json"), JSON.writeValueAsString(java.util.Map.of(
                "documentFullName", documentName,
                "sourceSha256", sourceHash,
                "documentMarkdown", "document.md",
                "documentMarkdownSha256", sha256(document),
                "questionCount", 1,
                "questions", List.of(java.util.Map.of(
                        "questionNumber", "1", "questionMarkdown", "questions/q-001.md",
                        "questionMarkdownSha256", sha256(question), "sourcePages", List.of(1),
                        "assets", List.of(java.util.Map.of(
                                "assetId", "figure-asset-1", "assetSha256", sha256(figure),
                                "canonicalAssetPath", "figures/q-001-01.png")))))),
                StandardCharsets.UTF_8);
        CanonicalMathPaperAssetService service = new CanonicalMathPaperAssetService(tempDir);
        String documentRef = uuid5(documentName + "\n" + sourceHash);
        RequestSubject subject = new RequestSubject("tenant", "teacher", "teacher-1", "agent-worker").normalize();

        var opened = service.openVisibleQuestionFigure(documentRef, "1", "figures/q-001-01.png", subject);

        assertThat(opened).isPresent();
        assertThat(opened.get().mimeType()).isEqualTo("image/png");
        try (var input = opened.get().resource().getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo("figure-bytes".getBytes(StandardCharsets.UTF_8));
        }

        // Page images, cross-question reuse, and forged document references all fail closed.
        assertThat(service.openVisibleQuestionFigure(documentRef, "1", "page-images/page-01.png", subject)).isEmpty();
        assertThat(service.openVisibleQuestionFigure(documentRef, "2", "figures/q-001-01.png", subject)).isEmpty();
        assertThat(service.openVisibleQuestionFigure(documentRef, "1", "../source-manifest.json", subject)).isEmpty();
        assertThat(service.openVisibleQuestionFigure("doc_forged", "1", "figures/q-001-01.png", subject)).isEmpty();

        // A figure asset whose bytes no longer match the manifest hash must not resolve for rendering.
        Files.write(figure, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThat(service.openVisibleQuestionFigure(documentRef, "1", "figures/q-001-01.png", subject)).isEmpty();
    }

    private static String sha256(Path source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
    }

    private static String sha256(byte[] source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    }

    private static String uuid5(String value) throws Exception {
        java.util.UUID namespace = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        byte[] namespaceBytes = java.nio.ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array();
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(namespaceBytes);
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new java.util.UUID(java.nio.ByteBuffer.wrap(hash, 0, 8).getLong(),
                java.nio.ByteBuffer.wrap(hash, 8, 8).getLong()).toString();
    }
}
