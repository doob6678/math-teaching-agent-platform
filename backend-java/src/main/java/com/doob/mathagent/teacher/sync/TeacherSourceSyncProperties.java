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
                feishuStagingRoot.resolve("_assets"),
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
                feishuStagingRoot.resolve("_assets"),
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
                        environment.getProperty(
                                "math-agent.teacher.sync.feishu.staging-root",
                                Path.of(System.getProperty("user.dir", "."), ".local-storage", "teacher-source-imports").toString()) + "/_assets")),
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
