package com.doob.mathagent.teacher.sync;

import java.nio.file.Path;
import org.springframework.core.env.Environment;

/**
 * Teacher source synchronization runtime paths and limits.
 *
 * @param feishuDefaultUrl optional display-only Feishu folder URL; runtime download/discovery requests must pass URLs explicitly
 * @param feishuDownloaderScript Python downloader script path
 * @param feishuAppkeyPath APPKEY file path used by the downloader; raw content is never returned by APIs
 * @param feishuStagingRoot local staging root for downloaded Feishu resources
 * @param assetStorageRoot backend-owned root for extracted images/assets; controllers never expose this path
 * @param feishuSmokeMaxFiles bounded file count for smoke tests and UI-triggered checks
 * @param feishuProcessTimeoutSeconds hard timeout for one Python Feishu process attempt
 */
public record TeacherSourceSyncProperties(
        String feishuDefaultUrl,
        Path feishuDownloaderScript,
        Path feishuAppkeyPath,
        Path feishuStagingRoot,
        Path assetStorageRoot,
        int feishuSmokeMaxFiles,
        int feishuProcessTimeoutSeconds) {

    /**
     * Keeps the shorter constructor shape for explicit call sites that do not need a custom timeout.
     */
    public TeacherSourceSyncProperties(
            String feishuDefaultUrl,
            Path feishuDownloaderScript,
            Path feishuAppkeyPath,
            Path feishuStagingRoot,
            int feishuSmokeMaxFiles) {
        this(
                feishuDefaultUrl,
                feishuDownloaderScript,
                feishuAppkeyPath,
                feishuStagingRoot,
                defaultAssetStorageRoot(feishuStagingRoot),
                feishuSmokeMaxFiles,
                30);
    }

    public TeacherSourceSyncProperties(
            String feishuDefaultUrl,
            Path feishuDownloaderScript,
            Path feishuAppkeyPath,
            Path feishuStagingRoot,
            int feishuSmokeMaxFiles,
            int feishuProcessTimeoutSeconds) {
        this(
                feishuDefaultUrl,
                feishuDownloaderScript,
                feishuAppkeyPath,
                feishuStagingRoot,
                defaultAssetStorageRoot(feishuStagingRoot),
                feishuSmokeMaxFiles,
                feishuProcessTimeoutSeconds);
    }

    /**
     * Creates properties from Spring configuration.
     *
     * @param environment Spring environment
     * @return normalized teacher source sync properties
     */
    public static TeacherSourceSyncProperties fromSpringEnvironment(Environment environment) {
        return new TeacherSourceSyncProperties(
                textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.default-url"),
                        ""),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.downloader-script"),
                        defaultFeishuDownloaderScript().toString())),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.appkey-path"),
                        "")),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.staging-root"),
                        Path.of(System.getProperty("user.dir", "."), ".local-storage", "teacher-source-imports").toString())),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.asset-storage-root"),
                        defaultAssetStorageRoot(Path.of(textOrDefault(
                                environment.getProperty("math-agent.teacher.sync.feishu.staging-root"),
                                Path.of(System.getProperty("user.dir", "."), ".local-storage", "teacher-source-imports").toString()))).toString())),
                integerOrDefault(environment.getProperty("math-agent.teacher.sync.feishu.smoke-max-files"), 1),
                integerOrDefault(environment.getProperty("math-agent.teacher.sync.feishu.process-timeout-seconds"), 30));
    }

    /**
     * Normalizes configured paths and clamps max files to a non-negative value.
     */
    public TeacherSourceSyncProperties {
        feishuDownloaderScript = feishuDownloaderScript.toAbsolutePath().normalize();
        feishuAppkeyPath = feishuAppkeyPath.toAbsolutePath().normalize();
        feishuStagingRoot = feishuStagingRoot.toAbsolutePath().normalize();
        assetStorageRoot = assetStorageRoot.toAbsolutePath().normalize();
        if (feishuStagingRoot.equals(assetStorageRoot)
                || feishuStagingRoot.startsWith(assetStorageRoot)
                || assetStorageRoot.startsWith(feishuStagingRoot)) {
            throw new IllegalArgumentException("Teacher source and asset roots must be independent directories");
        }
        feishuSmokeMaxFiles = Math.max(0, feishuSmokeMaxFiles);
        feishuProcessTimeoutSeconds = Math.max(1, feishuProcessTimeoutSeconds);
    }

    /**
     * Checks the same credential precedence used by the Python worker without exposing either credential value.
     * Environment credentials are preferred for deployment; the APPKEY file remains a local-development fallback.
     */
    public boolean credentialsConfigured() {
        String appId = firstEnvironmentValue("APP_ID", "FEISHU_APP_ID", "FEISHU_APPID");
        String appSecret = firstEnvironmentValue("APP_SECRET", "FEISHU_APP_SECRET", "FEISHU_APPSECRET");
        return (appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank())
                || java.nio.file.Files.isRegularFile(feishuAppkeyPath());
    }

    /** Resolves deployment aliases without ever copying secret values into Spring configuration or logs. */
    private static String firstEnvironmentValue(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Parses a positive integer property or returns fallback.
     */
    private static int integerOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static Path defaultAssetStorageRoot(Path stagingRoot) {
        Path normalized = stagingRoot.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return (parent == null ? normalized.resolveSibling("teacher-assets") : parent.resolve("teacher-assets"))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Validates a source document path. Feishu source text is authoritative only inside the staging volume; the
     * asset volume is deliberately a sibling root and can never become a source fallback.
     */
    public Path requireStagingPath(Path path) {
        Path candidate = java.util.Objects.requireNonNull(path, "staging path is required").toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(candidate)) {
            throw new IllegalArgumentException("Teacher staging path does not exist");
        }
        try {
            Path realCandidate = candidate.toRealPath();
            Path realStaging = realPathOrNormalized(feishuStagingRoot);
            Path realAssets = realPathOrNormalized(assetStorageRoot);
            if (realCandidate.equals(realStaging)
                    || !realCandidate.startsWith(realStaging)
                    || realCandidate.equals(realAssets)
                    || realCandidate.startsWith(realAssets)) {
                throw new IllegalArgumentException("Teacher staging path is outside the configured source volume");
            }
            return realCandidate;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Teacher staging path cannot be resolved", exception);
        }
    }

    public Path requireSourceRoot(Path root) {
        Path candidate = requireStagingPath(root);
        try {
            if (!containsSupportedSourceFile(candidate)) {
                throw new IllegalArgumentException("Teacher source root contains no supported text file");
            }
            return candidate;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Teacher source root cannot be resolved", exception);
        }
    }

    public boolean isValidSourceRoot(Path root) {
        try {
            requireSourceRoot(root);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean containsSupportedSourceFile(Path root) throws java.io.IOException {
        if (java.nio.file.Files.isRegularFile(root)) {
            return isSupportedSourceFile(root);
        }
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(root)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return path.toRealPath().startsWith(root) && isSupportedSourceFile(path);
                        } catch (java.io.IOException exception) {
                            return false;
                        }
                    })
                    .findAny()
                    .isPresent();
        }
    }

    private static boolean isSupportedSourceFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt");
    }

    private static Path realPathOrNormalized(Path path) throws java.io.IOException {
        return java.nio.file.Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    }

    /**
     * Resolves the project-owned Feishu downloader script from common backend and repo working directories.
     *
     * @return project-owned downloader script path
     */
    private static Path defaultFeishuDownloaderScript() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve("ai-worker-python/scripts/download_feishu_url.py"),
                cwd.resolve("../ai-worker-python/scripts/download_feishu_url.py"),
                cwd.resolve("../../ai-worker-python/scripts/download_feishu_url.py")
        };
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }
}
