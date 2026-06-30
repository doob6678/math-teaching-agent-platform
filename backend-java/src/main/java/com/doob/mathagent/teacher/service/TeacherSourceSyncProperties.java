package com.doob.mathagent.teacher.service;

import java.nio.file.Path;
import org.springframework.core.env.Environment;

/**
 * Teacher source synchronization runtime paths and limits.
 *
 * @param feishuDefaultUrl default Feishu test folder URL shown by the teacher UI
 * @param feishuDownloaderScript Python downloader script path
 * @param feishuAppkeyPath APPKEY file path used by the downloader; raw content is never returned by APIs
 * @param feishuStagingRoot local staging root for downloaded Feishu resources
 * @param feishuSmokeMaxFiles bounded file count for smoke tests and UI-triggered checks
 * @param feishuProcessTimeoutSeconds hard timeout for one Python Feishu process attempt
 */
public record TeacherSourceSyncProperties(
        String feishuDefaultUrl,
        Path feishuDownloaderScript,
        Path feishuAppkeyPath,
        Path feishuStagingRoot,
        int feishuSmokeMaxFiles,
        int feishuProcessTimeoutSeconds) {

    /**
     * Keeps the previous constructor shape for tests and callers that do not need a custom timeout.
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
                feishuSmokeMaxFiles,
                30);
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
                        "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f"),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.downloader-script"),
                        defaultFeishuDownloaderScript().toString())),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.appkey-path"),
                        "D:/project2026/feishutest/APPKEY.md")),
                Path.of(textOrDefault(
                        environment.getProperty("math-agent.teacher.sync.feishu.staging-root"),
                        "D:/project2026/feishutest/codex-app-staging")),
                integerOrDefault(environment.getProperty("math-agent.teacher.sync.feishu.smoke-max-files"), 1),
                integerOrDefault(environment.getProperty("math-agent.teacher.sync.feishu.process-timeout-seconds"), 30));
    }

    /**
     * Creates safe local defaults for tests and no-Spring construction paths.
     *
     * @return default teacher source sync properties
     */
    public static TeacherSourceSyncProperties defaults() {
        return new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                defaultFeishuDownloaderScript(),
                Path.of("D:/project2026/feishutest/APPKEY.md"),
                Path.of("D:/project2026/feishutest/codex-app-staging"),
                1,
                30);
    }

    /**
     * Normalizes configured paths and clamps max files to a non-negative value.
     */
    public TeacherSourceSyncProperties {
        feishuDownloaderScript = feishuDownloaderScript.toAbsolutePath().normalize();
        feishuAppkeyPath = feishuAppkeyPath.toAbsolutePath().normalize();
        feishuStagingRoot = feishuStagingRoot.toAbsolutePath().normalize();
        feishuSmokeMaxFiles = Math.max(0, feishuSmokeMaxFiles);
        feishuProcessTimeoutSeconds = Math.max(1, feishuProcessTimeoutSeconds);
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
