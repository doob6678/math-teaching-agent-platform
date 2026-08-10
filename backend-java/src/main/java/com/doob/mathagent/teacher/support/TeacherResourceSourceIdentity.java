package com.doob.mathagent.teacher.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates a stable identity for a registered source without relying on its mutable display title.
 *
 * Feishu document and folder tokens are immutable across rename operations, whereas copied browser URLs may contain
 * query parameters or different hosts. Local sources use an absolute normalized path because that is the durable
 * source-side identity available to this application.
 */
public final class TeacherResourceSourceIdentity {

    private static final Pattern FEISHU_RESOURCE = Pattern.compile(
            "https?://[^/]*feishu\\.cn/(?:drive/folder|docx|file)/([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    private TeacherResourceSourceIdentity() {
    }

    /**
     * Resolves a canonical identity from source data.
     *
     * @param sourceType normalized source type
     * @param originalUrl remote address, when applicable
     * @param localPath local source path, when applicable
     * @return stable canonical identity
     */
    public static String resolve(String sourceType, String originalUrl, String localPath) {
        String normalizedType = requireText(sourceType, "sourceType is required").toLowerCase(Locale.ROOT);
        if ("feishu".equals(normalizedType)) {
            Matcher matcher = FEISHU_RESOURCE.matcher(requireText(originalUrl, "Feishu originalUrl is required"));
            if (!matcher.find()) {
                throw new IllegalArgumentException("Unsupported Feishu URL. Expected a document, file, or folder token");
            }
            String matched = matcher.group();
            String pathKind = matched.contains("/drive/folder/") ? "folder"
                    : matched.contains("/docx/") ? "docx" : "file";
            return "feishu:" + pathKind + ":" + matcher.group(1);
        }
        if (localPath != null && !localPath.isBlank()) {
            String sourcePath = localPath.strip();
            try {
                Path normalized = Path.of(sourcePath).toAbsolutePath().normalize();
                try {
                    normalized = normalized.toRealPath();
                } catch (IOException ignored) {
                    // Registration keeps the normalized target even when a removable local volume is temporarily offline.
                }
                return "local:" + normalized.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            } catch (InvalidPathException ignored) {
                // Historical cross-platform paths remain auditable even when the current host cannot parse them.
                return "local:" + sourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
            }
        }
        return "url:" + requireText(originalUrl, "originalUrl is required");
    }

    /**
     * Returns the fixed-width key used by the MySQL uniqueness constraint.
     *
     * @param identity canonical source identity
     * @return SHA-256 hex digest
     */
    public static String hash(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requireText(identity, "source identity is required")
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
