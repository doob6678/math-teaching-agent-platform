package com.doob.mathagent.teacher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feishu downloader backed by the verified Python URL downloader script.
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.teacher.sync.feishu", name = "process-downloader-enabled", havingValue = "true")
public class ProcessTeacherFeishuDownloadClient implements TeacherFeishuDownloadClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 800L;
    private static final int MAX_ERROR_OUTPUT_CHARS = 4000;
    private static final Pattern SAVED_PATH_PATTERN = Pattern.compile("\"saved_path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FILES_PATTERN = Pattern.compile("\"files\"\\s*:\\s*(\\d+)");
    private static final Pattern SKIPPED_PATTERN = Pattern.compile("\"skipped\"\\s*:\\s*(\\d+)");
    private static final Pattern FAILED_PATTERN = Pattern.compile("\"failed\"\\s*:\\s*(\\d+)");

    private final TeacherSourceSyncProperties properties;

    /**
     * Creates a process-backed downloader.
     *
     * @param properties teacher source sync properties
     */
    public ProcessTeacherFeishuDownloadClient(TeacherSourceSyncProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs the Python downloader with an APPKEY path and a temporary summary file.
     */
    @Override
    public FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
        validateConfiguredFiles();
        Path outputRoot = stagingRoot.toAbsolutePath().normalize();
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return runDownloader(url, outputRoot, maxFiles, attempt);
            } catch (IllegalStateException exception) {
                lastFailure = exception;
                if (attempt == MAX_ATTEMPTS || !isRetryable(exception)) {
                    throw exception;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Feishu downloader failed") : lastFailure;
    }

    /**
     * Runs one bounded downloader process attempt.
     */
    private FeishuDownloadResult runDownloader(String url, Path outputRoot, int maxFiles, int attempt) {
        Path summaryPath = outputRoot.resolve("summary-" + Instant.now().toEpochMilli() + "-attempt-" + attempt + ".json");
        try {
            Files.createDirectories(outputRoot);
            Process process = new ProcessBuilder(
                    "python",
                    properties.feishuDownloaderScript().toString(),
                    "--url",
                    url,
                    "--appkey-path",
                    properties.feishuAppkeyPath().toString(),
                    "--output-dir",
                    outputRoot.toString(),
                    "--summary-path",
                    summaryPath.toString(),
                    "--max-files",
                    String.valueOf(maxFiles),
                    "--quiet")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Feishu downloader failed with exit " + exitCode + ": " + safeProcessOutput(output));
            }
            String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
            int failed = intField(summary, FAILED_PATTERN);
            if (failed > 0) {
                throw new IllegalStateException("Feishu downloader reported failed files: " + failed);
            }
            Path savedPath = Path.of(textField(summary, SAVED_PATH_PATTERN, outputRoot.toString()));
            int files = intField(summary, FILES_PATTERN);
            int skipped = intField(summary, SKIPPED_PATTERN);
            return new FeishuDownloadResult(
                    savedPath,
                    files,
                    skipped,
                    failed,
                    "Downloaded " + files + " Feishu files; skipped " + skipped);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run Feishu downloader", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu downloader was interrupted", exception);
        }
    }

    /**
     * Retries transient proxy and network failures from the verified Feishu script.
     */
    private static boolean isRetryable(IllegalStateException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("proxyerror")
                || message.contains("connection")
                || message.contains("timeout")
                || message.contains("timed out")
                || message.contains("remote end closed")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504");
    }

    /**
     * Backs off briefly between retryable downloader attempts.
     */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu downloader retry was interrupted", exception);
        }
    }

    /**
     * Caps subprocess output so API/job messages cannot become oversized logs.
     */
    private static String safeProcessOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String normalized = output.strip();
        if (normalized.length() <= MAX_ERROR_OUTPUT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_OUTPUT_CHARS) + "...";
    }

    /**
     * Validates local files needed by the process downloader.
     */
    private void validateConfiguredFiles() {
        if (!Files.isRegularFile(properties.feishuDownloaderScript())) {
            throw new IllegalStateException("Feishu downloader script not found");
        }
        if (!Files.isRegularFile(properties.feishuAppkeyPath())) {
            throw new IllegalStateException("Feishu APPKEY path not found");
        }
    }

    /**
     * Extracts a JSON text field from the summary.
     */
    private static String textField(String json, Pattern pattern, String defaultValue) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\\", "\\") : defaultValue;
    }

    /**
     * Extracts a JSON integer field from the summary.
     */
    private static int intField(String json, Pattern pattern) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
