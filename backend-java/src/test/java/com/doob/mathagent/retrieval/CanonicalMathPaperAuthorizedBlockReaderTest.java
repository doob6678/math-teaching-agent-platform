package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validates canonical reads remain bound to the manifest's full-document and question-file hashes. */
class CanonicalMathPaperAuthorizedBlockReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readsOnlyHashBoundPublishedPaperAndOneManifestAuthorizedQuestion() throws Exception {
        String documentName = "2024全国甲卷数学.pdf";
        String sourceHash = sha256("original-pdf".getBytes(StandardCharsets.UTF_8));
        Path paperRoot = tempDir.resolve(documentName);
        Files.createDirectories(paperRoot.resolve("questions"));
        Path document = paperRoot.resolve("document.md");
        Path question = paperRoot.resolve("questions/q-001.md");
        Files.writeString(document, """
                # 2024全国甲卷数学
                ## 第 1 页
                1. 已知集合 $A$。
                ## 第 2 页
                2. 设函数 $f(x)$。
                """, StandardCharsets.UTF_8);
        Files.writeString(question, "# 2024全国甲卷数学 第 1 题\n\n原始题干 $A$。\n", StandardCharsets.UTF_8);
        Files.writeString(paperRoot.resolve("source-manifest.json"), JSON.writeValueAsString(java.util.Map.of(
                "documentFullName", documentName,
                "sourceSha256", sourceHash,
                "documentMarkdown", "document.md",
                "documentMarkdownSha256", sha256(document),
                "questionCount", 1,
                "questions", List.of(java.util.Map.of(
                        "questionNumber", "1", "questionMarkdown", "questions/q-001.md",
                        "questionMarkdownSha256", sha256(question), "sourcePages", List.of(1))))), StandardCharsets.UTF_8);
        CanonicalMathPaperAuthorizedBlockReader reader = new CanonicalMathPaperAuthorizedBlockReader(tempDir);
        String authorizedDocumentRef = uuid5(documentName + "\n" + sourceHash);

        assertThat(reader.isAvailable(authorizedDocumentRef)).isTrue();
        assertThat(reader.read(authorizedDocumentRef)).extracting(TeacherDocumentBlockResponse::rawText)
                .contains("## 第 1 页\n1. 已知集合 $A$。", "## 第 2 页\n2. 设函数 $f(x)$。");
        assertThat(reader.readQuestion(authorizedDocumentRef, "1")).extracting(TeacherDocumentBlockResponse::rawText)
                .containsExactly("# 2024全国甲卷数学 第 1 题\n\n原始题干 $A$。\n");
        assertThatThrownBy(() -> reader.readQuestion(authorizedDocumentRef, "../../1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readQuestion(authorizedDocumentRef, "2"))
                .isInstanceOf(IllegalArgumentException.class);

        Files.writeString(question, "篡改后的题目", StandardCharsets.UTF_8);
        assertThat(reader.isAvailable(authorizedDocumentRef)).isFalse();
    }

    @Test
    void projectsOnlyManifestBoundQuestionFigureRowsForAuthorizedQuestionReads() throws Exception {
        String documentName = "2024立体几何真题.pdf";
        String sourceHash = sha256("original-solid-geometry-pdf".getBytes(StandardCharsets.UTF_8));
        Path paperRoot = tempDir.resolve(documentName);
        Files.createDirectories(paperRoot.resolve("questions"));
        Files.createDirectories(paperRoot.resolve("figures"));
        Path document = paperRoot.resolve("document.md");
        Path question = paperRoot.resolve("questions/q-001.md");
        Path figure = paperRoot.resolve("figures/q-001-01.png");
        Path pageImage = paperRoot.resolve("page-images/page-01.png");
        Files.writeString(document, """
                # 2024立体几何真题
                ## 第 1 页
                1. 如图，四棱锥。
                """, StandardCharsets.UTF_8);
        Files.writeString(question, """
                # 2024立体几何真题 第 1 题

                如图，四棱锥 P-ABCD。

                ![第 1 题图](figures/q-001-01.png)
                """, StandardCharsets.UTF_8);
        Files.write(figure, "figure-bytes".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(paperRoot.resolve("page-images"));
        Files.write(pageImage, "page-bytes".getBytes(StandardCharsets.UTF_8));
        Files.writeString(paperRoot.resolve("source-manifest.json"), JSON.writeValueAsString(java.util.Map.of(
                "documentFullName", documentName,
                "sourceSha256", sourceHash,
                "documentMarkdown", "document.md",
                "documentMarkdownSha256", sha256(document),
                "questionCount", 1,
                "questions", List.of(java.util.Map.of(
                        "questionNumber", "1", "questionMarkdown", "questions/q-001.md",
                        "questionMarkdownSha256", sha256(question), "sourcePages", List.of(1),
                        "assets", List.of(
                                java.util.Map.of(
                                        "assetId", "figure-asset-1", "assetSha256", sha256(figure),
                                        "canonicalAssetPath", "figures/q-001-01.png"),
                                java.util.Map.of(
                                        "assetId", "page-asset-1", "assetSha256", sha256(pageImage),
                                        "canonicalAssetPath", "page-images/page-01.png")))))),
                StandardCharsets.UTF_8);
        CanonicalMathPaperAuthorizedBlockReader reader = new CanonicalMathPaperAuthorizedBlockReader(tempDir);
        String authorizedDocumentRef = uuid5(documentName + "\n" + sourceHash);

        JsonNode imageRefs = JSON.readTree(reader.readQuestion(authorizedDocumentRef, "1").getFirst().imageRefs());

        assertThat(imageRefs).hasSize(1);
        assertThat(imageRefs.get(0).path("markdownLine").asText())
                .isEqualTo("![第 1 题图](figures/q-001-01.png)");
        assertThat(imageRefs.get(0).path("logicalPath").asText()).isEqualTo("figures/q-001-01.png");
        assertThat(imageRefs.toString()).doesNotContain("assetId", "page-images", "/app/");

        // A figure whose manifest hash no longer matches the copied asset is excluded, never partially trusted.
        Files.write(figure, "tampered-figure-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readQuestion(authorizedDocumentRef, "1").getFirst().imageRefs()).isEqualTo("[]");
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
