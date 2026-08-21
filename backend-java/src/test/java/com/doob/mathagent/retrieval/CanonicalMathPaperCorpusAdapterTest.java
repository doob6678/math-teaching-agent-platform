package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalMathPaperCorpusAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsManifestAuthorizedProbabilityStatisticsHitToOpaqueEvidenceOnly() throws Exception {
        Path corpusRoot = Files.createTempDirectory("canonical-paper-corpus-");
        String documentName = "2024 高考概率统计.pdf";
        String sourceHash = "a".repeat(64);
        Path paperRoot = corpusRoot.resolve(documentName);
        Files.createDirectories(paperRoot.resolve("questions"));
        Path questionMarkdown = paperRoot.resolve("questions/q-019.md");
        Files.writeString(questionMarkdown, "# 第 19 题\n原题", java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(paperRoot.resolve("source-manifest.json"), JSON.writeValueAsString(
                java.util.Map.of("documentFullName", documentName, "sourceSha256", sourceHash,
                        "questions", List.of(java.util.Map.of("questionNumber", "19",
                                "questionMarkdown", "questions/q-019.md",
                                "questionMarkdownSha256", sha256(questionMarkdown),
                                "sourcePages", List.of(3),
                                "assetIds", List.of("asset-opaque", "page-opaque"))))));
        String documentRef = uuid5(documentName + "\n" + sourceHash).toString();
        var metadata = JSON.readTree("""
                {"documentFullName":"2024 高考概率统计.pdf","documentRef":"%s","sourceSha256":"%s",
                 "pageStart":3,"questionNumber":"19","questionAssets":[{"assetId":"asset-opaque","assetSha256":"b"}],
                 "pageAssetIds":["page-opaque"]}
                """.formatted(documentRef, sourceHash));

        var adapter = new CanonicalMathPaperCorpusAdapter(corpusRoot);
        var evidence = adapter.adapt(List.of(new TextbookMilvusSearchClient.MilvusHit(
                "vector-row", "概率统计题干", metadata, 0.92d)));

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.sourceTitle()).isEqualTo(documentName);
            assertThat(item.chunkId()).startsWith("paper_");
            assertThat(item.sourceDocumentId()).isEqualTo(documentRef);
            assertThat(item.snippet()).isEqualTo("概率统计题干");
            assertThat(item.assetIds()).containsExactly("asset-opaque", "page-opaque");
            assertThat(item.toString()).doesNotContain(corpusRoot.toString(), "document.md", "collection");
        });
    }

    @Test
    void rejectsAHitWhoseManifestBindingDoesNotMatch() throws Exception {
        Path corpusRoot = Files.createTempDirectory("canonical-paper-corpus-");
        String documentName = "paper.pdf";
        Files.createDirectories(corpusRoot.resolve(documentName));
        Files.writeString(corpusRoot.resolve(documentName).resolve("source-manifest.json"),
                "{\"documentFullName\":\"paper.pdf\",\"sourceSha256\":\"" + "a".repeat(64) + "\"}");
        var metadata = JSON.readTree("""
                {"documentFullName":"paper.pdf","documentRef":"forged","sourceSha256":"%s"}
                """.formatted("a".repeat(64)));

        assertThat(new CanonicalMathPaperCorpusAdapter(corpusRoot).adapt(List.of(
                new TextbookMilvusSearchClient.MilvusHit("row", "题干", metadata, 0.8d)))).isEmpty();
    }

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private static UUID uuid5(String value) {
        try {
            UUID namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            byte[] namespaceBytes = java.nio.ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array();
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            digest.update(namespaceBytes);
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(java.nio.ByteBuffer.wrap(hash, 0, 8).getLong(), java.nio.ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
