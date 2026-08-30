package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites authorized source Markdown image labels before a document block crosses into the Python writer.
 *
 * <p>The target remains the source-relative image path so the writer can reproduce the original Markdown row. The
 * label is a run-stable opaque alias; no asset id, storage key, absolute path, URL, or binary content crosses this
 * boundary. The exporter later resolves the unchanged logical path against the current subject authorization.</p>
 */
public final class HandoutDocumentImageRewriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:/.*");
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/jpeg", "image/jpg", "image/png");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    private static final int DEFAULT_MAX_IMAGES = 12;

    private final String labelPrefix;
    private final int maxImages;

    public HandoutDocumentImageRewriter(String labelPrefix, int maxImages) {
        String normalizedPrefix = labelPrefix == null ? "source-image" : labelPrefix.strip();
        if (!normalizedPrefix.matches("[A-Za-z][A-Za-z0-9._:-]{0,39}")) {
            throw new IllegalArgumentException("source image label prefix is invalid");
        }
        this.labelPrefix = normalizedPrefix;
        this.maxImages = Math.max(1, Math.min(maxImages, 50));
    }

    public HandoutDocumentImageRewriter() {
        this("source-image", DEFAULT_MAX_IMAGES);
    }

    @FunctionalInterface
    public interface ImageAuthorizer {
        boolean isAuthorized(String documentId, String logicalPath, RequestSubject subject);
    }

    /**
     * Rewrites every image-bearing block in source order. A false authorizer result fails closed instead of silently
     * passing an image row to the model without a later materialization path.
     */
    public List<TeacherDocumentBlockResponse> rewrite(
            String runId,
            List<TeacherDocumentBlockResponse> blocks,
            RequestSubject subject,
            ImageAuthorizer authorizer) {
        if (blocks == null || blocks.isEmpty()) {
            return blocks == null ? List.of() : List.copyOf(blocks);
        }
        String runFingerprint = safe(runId);
        int imageIndex = 0;
        List<TeacherDocumentBlockResponse> rewritten = new ArrayList<>(blocks.size());
        Map<String, String> aliasesByLine = new HashMap<>();
        for (TeacherDocumentBlockResponse block : blocks) {
            if (block == null || block.imageRefs() == null || block.imageRefs().isBlank()) {
                rewritten.add(block);
                continue;
            }
            List<ImageRef> refs = parseRefs(block.imageRefs());
            if (refs.isEmpty()) {
                rewritten.add(block);
                continue;
            }
            String text = block.rawText() == null || block.rawText().isBlank()
                    ? safe(block.normalizedText()) : block.rawText();
            String rewrittenText = text;
            List<Map<String, String>> rewrittenRefs = new ArrayList<>();
            boolean exceedsImageLimit = false;
            for (ImageRef ref : refs) {
                validateReference(ref);
                if (!rewrittenText.contains(ref.markdownLine())) {
                    throw new IllegalStateException("Authorized image Markdown row is missing from its document block");
                }
                if (authorizer != null && !authorizer.isAuthorized(block.documentId(), ref.logicalPath(), subject)) {
                    throw new IllegalStateException("Authorized image is unavailable for this handout run");
                }
                String aliasKey = block.documentId() + "|" + ref.markdownLine();
                String alias = aliasesByLine.get(aliasKey);
                if (alias == null) {
                    if (imageIndex >= maxImages) {
                        exceedsImageLimit = true;
                        break;
                    }
                    imageIndex++;
                    alias = labelPrefix + ":" + fingerprint(runFingerprint + "|" + block.documentId()) + "-image-"
                            + String.format(Locale.ROOT, "%03d", imageIndex);
                    aliasesByLine.put(aliasKey, alias);
                }
                rewrittenText = rewrittenText.replace(ref.markdownLine(), replaceAlt(ref.markdownLine(), alias));
                rewrittenRefs.add(Map.of(
                        "markdownLine", replaceAlt(ref.markdownLine(), alias),
                        "logicalPath", ref.logicalPath()));
            }
            // The model-visible response has a fixed image budget. Excluding the whole later block keeps an
            // over-budget source row from crossing the boundary unrevised and preserves the first source-order rows.
            if (exceedsImageLimit) {
                continue;
            }
            rewritten.add(copyBlock(block, rewrittenText, rewrittenRefs));
        }
        return List.copyOf(rewritten);
    }

    private static TeacherDocumentBlockResponse copyBlock(
            TeacherDocumentBlockResponse block, String text, List<Map<String, String>> imageRefs) {
        try {
            String imageJson = JSON.writeValueAsString(imageRefs);
            return new TeacherDocumentBlockResponse(
                    block.blockId(), block.documentId(), block.externalBlockId(), block.blockType(), block.blockOrder(),
                    block.chapter(), block.section(), block.pageNo(), block.printedPageNo(), block.sourcePath(),
                    block.blockRole(), text, text, imageJson, block.formulaRefs(), block.graphNodeIdsJson(),
                    block.graphTagNamesJson(), block.checksum(), block.confidence(), block.status());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not rewrite authorized image references", exception);
        }
    }

    private static List<ImageRef> parseRefs(String rawJson) {
        try {
            JsonNode root = JSON.readTree(rawJson);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("Authorized image metadata is not an array");
            }
            List<ImageRef> refs = new ArrayList<>();
            for (JsonNode value : root) {
                if (value == null || !value.isObject()) {
                    throw new IllegalStateException("Authorized image metadata contains an invalid row");
                }
                String line = value.path("markdownLine").asText("").strip();
                String logical = value.path("logicalPath").asText("").strip().replace('\\', '/');
                String mime = value.path("mimeType").asText("").strip().toLowerCase(Locale.ROOT);
                if (line.isBlank() && logical.isBlank()) {
                    continue;
                }
                refs.add(new ImageRef(line, logical, mime));
            }
            return List.copyOf(refs);
        } catch (IOException exception) {
            throw new IllegalStateException("Authorized image metadata is malformed", exception);
        }
    }

    private static void validateReference(ImageRef ref) {
        if (!ref.markdownLine().startsWith("![") || !ref.markdownLine().contains("](")) {
            throw new IllegalStateException("Authorized image Markdown row is invalid");
        }
        String target = ref.markdownLine().substring(ref.markdownLine().indexOf("](") + 2);
        if (!target.endsWith(")")) {
            throw new IllegalStateException("Authorized image Markdown target is invalid");
        }
        target = target.substring(0, target.length() - 1).strip();
        if (target.isBlank() || target.contains("http://") || target.contains("https://")
                || target.startsWith("data:") || target.startsWith("/") || WINDOWS_ABSOLUTE.matcher(target).matches()
                || hasParentSegment(target)) {
            throw new IllegalStateException("Authorized image Markdown target is unsafe");
        }
        String extension = extension(target);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalStateException("Authorized image format is unsupported");
        }
        if (ref.logicalPath().isBlank() || ref.logicalPath().startsWith("/") || WINDOWS_ABSOLUTE.matcher(ref.logicalPath()).matches()
                || hasParentSegment(ref.logicalPath()) || ref.logicalPath().contains("://")) {
            throw new IllegalStateException("Authorized image logical path is unsafe");
        }
        if (!ref.mimeType().isBlank() && !SUPPORTED_MIME_TYPES.contains(ref.mimeType())) {
            throw new IllegalStateException("Authorized image MIME type is unsupported");
        }
    }

    private static boolean hasParentSegment(String value) {
        for (String segment : value.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private static String replaceAlt(String markdownLine, String alias) {
        int marker = markdownLine.indexOf("](");
        return "![" + alias + markdownLine.substring(marker);
    }

    private static String extension(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        int query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot) : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ImageRef(String markdownLine, String logicalPath, String mimeType) {
    }
}
