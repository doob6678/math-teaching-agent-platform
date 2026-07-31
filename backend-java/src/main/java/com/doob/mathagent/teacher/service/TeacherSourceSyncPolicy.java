package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.feishu.FeishuCredential;
import com.doob.mathagent.feishu.FeishuCredentialService;
import com.doob.mathagent.feishu.FeishuResourceBindingService;
import com.doob.mathagent.teacher.formula.OmmlFormulaExtractor;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionProperties;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.VectorIndexSyncException;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.ParsedBlock;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.ImageReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.PendingAsset;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.StoredAssetReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.FormulaReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.FormulaVisionBudget;
import static com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.*;

/**
 * Stateless parsing, checksum, and provider payload policy extracted from source-sync execution.
 * IO orchestration remains in TeacherSourceSyncExecutionService; this class only transforms validated values.
 */
final class TeacherSourceSyncPolicy {
    private TeacherSourceSyncPolicy() {
        // Stateless policy component.
    }


    /**
     * Converts a stored checkpoint to the downloader protocol.
     */
    static TeacherFeishuDownloadClient.FeishuDownloadCheckpoint toDownloadCheckpoint(
            TeacherSourceSyncCheckpointResponse checkpoint) {
        if (checkpoint == null) {
            return TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty();
        }
        return new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                textOrDefault(checkpoint.currentFolderToken(), ""),
                textOrDefault(checkpoint.currentPath(), ""),
                textOrDefault(checkpoint.pageToken(), ""),
                jsonOrEmptyArray(checkpoint.visitedFolderTokensJson()),
                jsonOrEmptyArray(checkpoint.downloadedItemsJson()));
    }


    /**
     * Converts a worker checkpoint back into the persisted checkpoint shape.
     */
    static TeacherSourceSyncCheckpointResponse toStoredCheckpoint(
            TeacherResourceDocumentResponse document,
            TeacherSourceSyncJobResponse job,
            TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint,
            String failedItemsJson) {
        String rootToken = extractFeishuToken(textOrDefault(document.originalUrl(), ""));
        return new TeacherSourceSyncCheckpointResponse(
                job.jobId(),
                document.tenantId(),
                document.documentId(),
                rootToken,
                textOrDefault(checkpoint.currentFolderToken(), rootToken),
                textOrDefault(checkpoint.currentPath(), textOrDefault(document.title(), "Feishu source")),
                textOrDefault(checkpoint.pageToken(), null),
                jsonOrEmptyArray(checkpoint.visitedFolderTokensJson()),
                jsonOrEmptyArray(checkpoint.downloadedItemsJson()),
                jsonOrEmptyArray(failedItemsJson),
                2,
                Instant.now().toString());
    }


    /**
     * Builds a compact downloaded-items JSON array from a successful download result.
     */
    static String downloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        return "[{\"savedPath\":\"" + escapeJson(result.savedPath().toString()) + "\","
                + "\"files\":" + result.files() + ","
                + "\"skipped\":" + result.skipped() + ","
                + "\"failed\":" + result.failed() + "}]";
    }


    /**
     * Prefers provider item-level downloaded records and falls back to a compact aggregate row.
     */
    static String mergeDownloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        String itemLevel = jsonOrEmptyArray(result.downloadedItemsJson());
        return "[]".equals(itemLevel) ? downloadedItemsJson(result) : itemLevel;
    }


    /**
     * Builds a compact failed-items JSON array from a download failure.
     */
    static String failedItemsJson(RuntimeException exception, boolean retryable) {
        return "[{\"message\":\"" + escapeJson(textOrDefault(exception.getMessage(), exception.getClass().getSimpleName()))
                + "\",\"retryable\":" + retryable + "}]";
    }


    /**
     * Extracts a Feishu browser URL token without exposing any secret material.
     */
    static String extractFeishuToken(String url) {
        String normalized = textOrDefault(url, "");
        int slash = normalized.lastIndexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return normalized;
        }
        String tail = normalized.substring(slash + 1);
        int question = tail.indexOf('?');
        return question >= 0 ? tail.substring(0, question) : tail;
    }


    /**
     * Escapes a string for the small JSON snippets stored in checkpoint rows.
     */
    static String escapeJson(String value) {
        return textOrDefault(value, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }


    /**
     * Defaults blank JSON array fields to an empty array string.
     */

    static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }


    static String assetCountSuffix(int assetCount) {
        return assetCount > 0 ? "; Feishu manifest assets " + assetCount : "";
    }


    /**
     * Resolves downloader relative paths without letting manifest data escape the saved folder/file root.
     */
    static Path resolveDownloadedItemPath(Path savedPath, String relativePath) {
        Path base = savedPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(base)) {
            if (base.getFileName().toString().equals(relativePath)) {
                return base;
            }
            Path parent = base.getParent() == null ? base : base.getParent();
            Path resolved = parent.resolve(relativePath).normalize();
            if (!resolved.startsWith(parent)) {
                throw new IllegalArgumentException("Feishu downloaded item path escapes saved file parent");
            }
            return resolved;
        }
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Feishu downloaded item path escapes saved folder");
        }
        return resolved;
    }


    /**
     * Returns whether the already persisted vector generation is safe to retain for an unchanged Feishu body.
     *
     * <p>A content checksum only proves that the parsed text has not changed. It does not prove that a prior
     * embedding or Milvus rebuild succeeded. In particular, a transient Worker failure leaves the same checksum
     * alongside {@code failed} statuses. Treating that state as an unchanged success permanently strands the
     * document outside retrieval, so an unchanged re-sync must rebuild unless both persisted readiness markers are
     * explicitly ready.</p>
     *
     * @param document previously persisted resource state
     * @return true only when both embedding and index generations are verified ready
     */
    static boolean hasVerifiedVectorReadiness(TeacherResourceDocumentResponse document) {
        return "ready".equalsIgnoreCase(textOrDefault(document.embeddingStatus(), ""))
                && "ready".equalsIgnoreCase(textOrDefault(document.indexStatus(), ""));
    }


    /**
     * Applies the body fingerprint only after actual parser output exists. This deliberately ignores Feishu titles:
     * Markdown headings become chapter metadata and are not paragraph body text, so a pure rename does not churn
     * blocks or Milvus vectors.
     */
    static TeacherResourceDocumentResponse withSyncFingerprint(
            TeacherResourceDocumentResponse document, String contentChecksum) {
        return new TeacherResourceDocumentResponse(
                document.documentId(), document.tenantId(), document.ownerSubjectId(), document.sourceType(),
                document.title(), document.originalUrl(), document.localPath(), document.permissionScope(),
                document.syncStatus(), document.parseStatus(), document.embeddingStatus(), document.indexStatus(),

                document.feishuExportFormat(), document.previewFiles(), document.parseMode(),
                document.providerRevision(), contentChecksum, document.sourceIdentity());
    }


    /**
     * Produces a deterministic document-body checksum from real parsed blocks. Source paths, title/chapter labels,
     * generated asset ids, and provider filenames are excluded so presentation-only rename events remain metadata-only.
     */
    static String semanticContentChecksum(List<TeacherDocumentBlockResponse> blocks, String providerTitle) {
        List<TeacherDocumentBlockResponse> ordered = blocks.stream()
                .sorted(Comparator.comparingInt(TeacherDocumentBlockResponse::blockOrder)
                        .thenComparing(block -> textOrDefault(block.sourcePath(), "")))
                .toList();
        String canonical = java.util.stream.IntStream.range(0, ordered.size())
                .mapToObj(index -> {
                    TeacherDocumentBlockResponse block = ordered.get(index);
                    boolean firstBlock = index == 0;
                    String raw = firstBlock ? removeLeadingProviderTitle(block.rawText(), providerTitle) : block.rawText();
                    String normalized = firstBlock
                            ? removeLeadingProviderTitle(block.normalizedText(), providerTitle)
                            : block.normalizedText();
                    return normalizeText(raw) + "\n" + normalizeText(normalized);
                })
                .collect(java.util.stream.Collectors.joining("\n\u001e\n"));
        return sha256(canonical);
    }


    /**
     * Feishu's content endpoint currently emits the document title as a plain first line instead of a Markdown
     * heading. Remove exactly that leading provider-owned display value from the first parsed block only; ordinary
     * occurrences later in the teaching body remain part of the fingerprint and still cause a reindex.
     */
    static String removeLeadingProviderTitle(String value, String providerTitle) {
        String text = value == null ? "" : value;
        String title = normalizeText(providerTitle);
        if (title.isBlank()) {
            return text;
        }
        String normalized = normalizeText(text);
        if (!normalized.startsWith(title)) {
            return text;
        }
        if (normalized.length() == title.length()) {
            return "";
        }
        char separator = normalized.charAt(title.length());
        if (!Character.isWhitespace(separator)) {
            return text;
        }
        return normalized.substring(title.length()).strip();
    }
}
