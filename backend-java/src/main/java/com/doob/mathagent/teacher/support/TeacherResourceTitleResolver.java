package com.doob.mathagent.teacher.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves stable display titles for teacher resources.
 *
 * <p>The upload/register flow should not force operators to hand-write titles that already exist in the source
 * itself. This resolver keeps title fallback logic in one place so browser uploads, local paths, and Feishu links all
 * converge on the same naming behavior instead of drifting back to {@code untitled-teacher-resource}.</p>
 */
public final class TeacherResourceTitleResolver {

    public static final String UNTITLED = "untitled-teacher-resource";

    private TeacherResourceTitleResolver() {
    }

    /**
     * Prefers an explicit title, otherwise derives one from the registered path or URL.
     *
     * <p>Path fallback matters for service-side registration callers, while URL fallback keeps Feishu registration
     * usable even before discovery metadata has been selected.</p>
     */
    public static String resolveOrDefault(
            String requestedTitle,
            String sourceType,
            String originalUrl,
            String localPath) {
        String explicit = normalizeCandidate(requestedTitle);
        if (explicit != null) {
            return explicit;
        }
        String fromPath = deriveFromPath(localPath);
        if (fromPath != null) {
            return fromPath;
        }
        String fromUrl = deriveFromUrl(originalUrl);
        if (fromUrl != null) {
            return fromUrl;
        }
        String fromSourceType = normalizeCandidate(sourceType == null ? null : sourceType.replace('_', '-'));
        return fromSourceType == null ? UNTITLED : fromSourceType;
    }

    /**
     * Derives a resource title from uploaded browser paths.
     *
     * <p>Folder uploads preserve relative paths via multipart filenames, so a shared top-level segment should become
     * the document title. Loose multi-file uploads stay a single resource and therefore collapse to a concise batch
     * label instead of pretending each file is a separate document.</p>
     */
    public static String deriveFromUploadPaths(Collection<String> originalPaths) {
        if (originalPaths == null || originalPaths.isEmpty()) {
            return UNTITLED;
        }
        List<String> normalizedPaths = new ArrayList<>();
        LinkedHashSet<String> topLevelSegments = new LinkedHashSet<>();
        boolean hasNestedPath = false;
        for (String originalPath : originalPaths) {
            String normalizedPath = normalizePath(originalPath);
            if (normalizedPath == null) {
                continue;
            }
            normalizedPaths.add(normalizedPath);
            String[] segments = normalizedPath.split("/");
            if (segments.length > 1) {
                hasNestedPath = true;
            }
            topLevelSegments.add(segments[0]);
        }
        if (normalizedPaths.isEmpty()) {
            return UNTITLED;
        }
        if (hasNestedPath && topLevelSegments.size() == 1) {
            String folderTitle = normalizeCandidate(topLevelSegments.iterator().next());
            if (folderTitle != null) {
                return folderTitle;
            }
        }
        String firstLeaf = leafName(normalizedPaths.get(0));
        if (normalizedPaths.size() == 1) {
            return firstLeaf == null ? UNTITLED : firstLeaf;
        }
        return firstLeaf == null ? UNTITLED : firstLeaf + " 等" + normalizedPaths.size() + "个文件";
    }

    private static String deriveFromPath(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(localPath.strip());
            Path fileName = path.getFileName();
            if (fileName != null) {
                String title = normalizeCandidate(fileName.toString());
                if (title != null) {
                    return title;
                }
            }
        } catch (InvalidPathException ignored) {
            // Fall through to string-based segment extraction so malformed separators do not force untitled names.
        }
        return leafName(localPath);
    }

    private static String deriveFromUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            return null;
        }
        String raw = originalUrl.strip();
        try {
            URI uri = new URI(raw);
            String fromPath = leafName(uri.getPath());
            if (fromPath != null) {
                return fromPath;
            }
            return leafName(uri.getFragment());
        } catch (URISyntaxException ignored) {
            return leafName(raw);
        }
    }

    private static String leafName(String value) {
        String normalizedPath = normalizePath(value);
        if (normalizedPath == null) {
            return null;
        }
        int slash = normalizedPath.lastIndexOf('/');
        String leaf = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
        return normalizeCandidate(leaf);
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip()
                .replace('\\', '/')
                .replaceAll("[?#].*$", "")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeCandidate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip()
                .replace('\\', '/')
                .replaceAll("[?#].*$", "");
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replaceAll("\\.[A-Za-z0-9]{1,8}$", "").strip();
        return normalized.isBlank() ? null : normalized;
    }
}
