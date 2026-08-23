package com.doob.mathagent.retrieval;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 规范试卷语料的来源授权适配器。
 *
 * <p>统一检索层负责向量编码和 Milvus 召回；本适配器只核对受控目录中的来源清单，并把共享检索命中
 * 映射为不透明证据、文档和资产谱系。任何文件系统路径都在本类内部消耗，绝不进入返回证据。</p>
 */
@Component
public class CanonicalMathPaperCorpusAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private final Path corpusRoot;

    public CanonicalMathPaperCorpusAdapter(
            @Value("${math-agent.teaching.canonical-paper.corpus-root:/app/data/math-paper-corpus}") Path corpusRoot) {
        this.corpusRoot = corpusRoot.toAbsolutePath().normalize();
    }

    /** Returns true only after the ingestion owner has published at least one manifest-backed source document. */
    boolean hasPublishedCorpus() {
        if (!Files.isDirectory(corpusRoot)) {
            return false;
        }
        try (Stream<Path> children = Files.list(corpusRoot)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.resolve("source-manifest.json"))
                    .anyMatch(Files::isRegularFile);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 仅接纳清单中已绑定文件名、哈希和不透明文档引用的共享检索命中。 */
    public List<TeachingEvidence> adapt(List<TextbookMilvusSearchClient.MilvusHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<TeachingEvidence> result = new ArrayList<>();
        for (TextbookMilvusSearchClient.MilvusHit hit : hits) {
            JsonNode metadata = hit.metadata();
            if ("FULL_DOCUMENT".equals(metadata.path("recordType").asText(""))) {
                continue;
            }
            String documentName = metadata.path("documentFullName").asText("").strip();
            if (documentName.isBlank()) {
                // Older canonical ingestion rows used sourceFile. Resolve that filename against the published
                // manifest below; the manifest remains the authority for hash and documentRef.
                documentName = metadata.path("sourceFile").asText("").strip();
            }
            String documentRef = metadata.path("documentRef").asText("").strip();
            String sourceHash = metadata.path("sourceSha256").asText("").strip();
            if (documentRef.isBlank() || !sourceHash.matches("[0-9a-fA-F]{64}")) {
                ManifestIdentity identity = manifestIdentity(documentName);
                documentRef = identity.documentRef();
                sourceHash = identity.sourceHash();
            }
            String questionNumber = metadata.path("questionNumber").asText("").strip();
            if (!authorizedDocument(documentName, documentRef, sourceHash)
                    || !authorizedQuestion(documentName, questionNumber, metadata)) {
                continue;
            }
            result.add(new TeachingEvidence(
                    "CANONICAL_MATH_PAPER",
                    documentName,
                    opaqueEvidenceRef(documentRef, hit.id()),
                    metadata.path("pageStart").asInt(metadata.path("page").asInt(0)),
                    boundedExcerpt(hit.text()),
                    "",
                    "",
                    documentRef,
                    "canonical_math_paper",
                    "",
                    "",
                    assetIds(metadata),
                    questionNumber));
        }
        return List.copyOf(result);
    }

    private ManifestIdentity manifestIdentity(String documentName) {
        if (documentName == null || documentName.isBlank()) {
            return new ManifestIdentity("", "");
        }
        Path documentRoot = corpusRoot.resolve(documentName).normalize();
        Path manifest = documentRoot.resolve("source-manifest.json").normalize();
        if (!documentRoot.startsWith(corpusRoot) || !Files.isRegularFile(manifest)) {
            return new ManifestIdentity("", "");
        }
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest));
            String canonicalName = source.path("documentFullName").asText("").strip();
            String sourceHash = source.path("sourceSha256").asText("").strip();
            if (!documentName.equals(canonicalName) || !sourceHash.matches("[0-9a-fA-F]{64}")) {
                return new ManifestIdentity("", "");
            }
            return new ManifestIdentity(uuid5(documentName + "\\n" + sourceHash).toString(), sourceHash);
        } catch (Exception ignored) {
            return new ManifestIdentity("", "");
        }
    }

    private record ManifestIdentity(String documentRef, String sourceHash) {
    }

    /** 清单校验是读取/渲染桥接前的来源授权边界，向量元数据本身不构成授权。 */
    private boolean authorizedDocument(String documentName, String documentRef, String sourceHash) {
        if (documentName.isBlank() || documentRef.isBlank() || !sourceHash.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        Path documentRoot = corpusRoot.resolve(documentName).normalize();
        if (!documentRoot.startsWith(corpusRoot) || !Files.isDirectory(documentRoot)) {
            return false;
        }
        Path manifest = documentRoot.resolve("source-manifest.json").normalize();
        if (!manifest.startsWith(documentRoot) || !Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest));
            return documentName.equals(source.path("documentFullName").asText(""))
                    && sourceHash.equalsIgnoreCase(source.path("sourceSha256").asText(""))
                    && documentRef.equals(uuid5(documentName + "\n" + sourceHash).toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean authorizedQuestion(String documentName, String questionNumber, JsonNode metadata) {
        if (!questionNumber.matches("[1-9]\\d{0,2}")) {
            return false;
        }
        Path documentRoot = corpusRoot.resolve(documentName).normalize();
        Path manifest = documentRoot.resolve("source-manifest.json").normalize();
        if (!documentRoot.startsWith(corpusRoot) || !manifest.startsWith(documentRoot)) {
            return false;
        }
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest));
            for (JsonNode question : source.path("questions")) {
                if (!questionNumber.equals(question.path("questionNumber").asText(""))) {
                    continue;
                }
                List<String> indexedAssets = new ArrayList<>();
                for (JsonNode assetId : question.path("assetIds")) {
                    String value = assetId.asText("").strip();
                    if (!value.isBlank()) {
                        indexedAssets.add(value);
                    }
                }
                for (JsonNode asset : question.path("assets")) {
                    String value = asset.path("assetId").asText("").strip();
                    if (!value.isBlank()) {
                        indexedAssets.add(value);
                    }
                }
                List<String> metadataAssets = assetIds(metadata);
                boolean legacyRowWithoutAssetBinding = !metadata.has("questionAssets")
                        && !metadata.has("pageAssetIds")
                        && !metadata.has("assetIds");
                return (legacyRowWithoutAssetBinding || indexedAssets.equals(metadataAssets))
                        && question.path("sourcePages").isArray()
                        && question.path("sourcePages").size() > 0;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
    private static List<String> assetIds(JsonNode metadata) {
        List<String> assetIds = new ArrayList<>();
        for (JsonNode asset : metadata.path("questionAssets")) {
            String assetId = asset.path("assetId").asText("").strip();
            if (!assetId.isBlank()) {
                assetIds.add(assetId);
            }
        }
        for (JsonNode assetId : metadata.path("pageAssetIds")) {
            String value = assetId.asText("").strip();
            if (!value.isBlank()) {
                assetIds.add(value);
            }
        }
        return assetIds.stream().distinct().toList();
    }

    private static String opaqueEvidenceRef(String documentRef, String id) {
        return "paper_" + uuid5(documentRef + "\n" + id);
    }

    /** 与 Python uuid.uuid5(UUID.NAMESPACE_URL, value) 保持一致，保证跨语言来源引用可复算。 */
    private static UUID uuid5(String value) {
        try {
            byte[] namespace = ByteBuffer.allocate(16)
                    .putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits())
                    .array();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(namespace);
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static String boundedExcerpt(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1200).strip();
    }
}
