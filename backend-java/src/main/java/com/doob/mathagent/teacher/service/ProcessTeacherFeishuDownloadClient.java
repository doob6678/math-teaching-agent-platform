package com.doob.mathagent.teacher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    private static final Pattern CURRENT_FOLDER_PATTERN = Pattern.compile("\"current_folder_token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CURRENT_PATH_PATTERN = Pattern.compile("\"current_path\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PAGE_TOKEN_PATTERN = Pattern.compile("\"page_token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VISITED_FOLDERS_PATTERN = Pattern.compile(
            "\"visited_folder_tokens\"\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*,\\s*\"downloaded_items\"");
    private static final Pattern DOWNLOADED_ITEMS_PATTERN = Pattern.compile(
            "\"downloaded_items\"\\s*:\\s*(\\[[\\s\\S]*?\\])");

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
    public FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            FeishuDownloadCheckpoint checkpoint) {
        validateConfiguredFiles();
        Path outputRoot = stagingRoot.toAbsolutePath().normalize();
        IllegalStateException lastFailure = null;
        FeishuDownloadCheckpoint activeCheckpoint = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return runDownloader(url, outputRoot, maxFiles, activeCheckpoint, attempt);
            } catch (IllegalStateException exception) {
                lastFailure = exception;
                FeishuDownloadCheckpoint latestCheckpoint = latestCheckpoint(exception);
                if (latestCheckpoint.hasCursor()) {
                    activeCheckpoint = latestCheckpoint;
                }
                boolean retryable = isRetryable(exception);
                if (attempt == MAX_ATTEMPTS && retryable) {
                    throw new TeacherFeishuDownloadException(
                            exception.getMessage(),
                            true,
                            exception,
                            activeCheckpoint);
                }
                if (attempt == MAX_ATTEMPTS || !retryable) {
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
    private FeishuDownloadResult runDownloader(
            String url,
            Path outputRoot,
            int maxFiles,
            FeishuDownloadCheckpoint checkpoint,
            int attempt) {
        Path summaryPath = outputRoot.resolve("summary-" + Instant.now().toEpochMilli() + "-attempt-" + attempt + ".json");
        Path checkpointPath = outputRoot.resolve("resume-checkpoint-" + Instant.now().toEpochMilli()
                + "-attempt-" + attempt + ".json");
        try {
            Files.createDirectories(outputRoot);
            Process process = new ProcessBuilder(buildCommand(
                    url,
                    outputRoot,
                    summaryPath,
                    checkpointPath,
                    maxFiles,
                    checkpoint))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ProcessDownloadFailure(
                        "Feishu downloader failed with exit " + exitCode + ": " + safeProcessOutput(output),
                        readCheckpoint(checkpointPath));
            }
            String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
            int failed = intField(summary, FAILED_PATTERN);
            if (failed > 0) {
                throw new ProcessDownloadFailure(
                        "Feishu downloader reported failed files: " + failed,
                        readCheckpoint(checkpointPath));
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
     * Builds a subprocess command without embedding secret material.
     */
    private List<String> buildCommand(
            String url,
            Path outputRoot,
            Path summaryPath,
            Path checkpointPath,
            int maxFiles,
            FeishuDownloadCheckpoint checkpoint) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of(
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
                "--quiet"));
        FeishuDownloadCheckpoint normalized = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
        if (normalized.hasCursor()) {
            Files.writeString(checkpointPath, checkpointJson(normalized), StandardCharsets.UTF_8);
            command.add("--resume-checkpoint-path");
            command.add(checkpointPath.toString());
        }
        return command;
    }

    /**
     * Builds the UTF-8 checkpoint file consumed by the Python downloader.
     */
    private static String checkpointJson(FeishuDownloadCheckpoint checkpoint) {
        return "{"
                + "\"current_folder_token\":\"" + escapeJson(checkpoint.currentFolderToken()) + "\","
                + "\"page_token\":\"" + escapeJson(checkpoint.pageToken()) + "\","
                + "\"current_path\":\"" + escapeJson(checkpoint.currentPath()) + "\","
                + "\"visited_folder_tokens\":" + jsonArrayOrEmpty(checkpoint.visitedFolderTokensJson()) + ","
                + "\"downloaded_items\":" + jsonArrayOrEmpty(checkpoint.downloadedItemsJson())
                + "}";
    }

    /**
     * Escapes JSON string values written to the checkpoint file.
     */
    private static String escapeJson(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * Keeps persisted JSON arrays intact and defaults blank values.
     */
    private static String jsonArrayOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return "[]";
        }
        String normalized = value.strip();
        return normalized.startsWith("[") && normalized.endsWith("]") ? normalized : "[]";
    }

    /**
     * Reads a downloader checkpoint file written by the Python worker.
     */
    private static FeishuDownloadCheckpoint readCheckpoint(Path checkpointPath) {
        if (!Files.isRegularFile(checkpointPath)) {
            return FeishuDownloadCheckpoint.empty();
        }
        try {
            String json = Files.readString(checkpointPath, StandardCharsets.UTF_8);
            return new FeishuDownloadCheckpoint(
                    textField(json, CURRENT_FOLDER_PATTERN, ""),
                    textField(json, CURRENT_PATH_PATTERN, ""),
                    textField(json, PAGE_TOKEN_PATTERN, ""),
                    textField(json, VISITED_FOLDERS_PATTERN, "[]"),
                    textField(json, DOWNLOADED_ITEMS_PATTERN, "[]"));
        } catch (IOException exception) {
            return FeishuDownloadCheckpoint.empty();
        }
    }

    /**
     * Extracts the latest checkpoint carried by an internal process failure.
     */
    private static FeishuDownloadCheckpoint latestCheckpoint(IllegalStateException exception) {
        return exception instanceof ProcessDownloadFailure failure
                ? failure.checkpoint()
                : FeishuDownloadCheckpoint.empty();
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

    /**
     * Internal process failure that carries the last worker checkpoint.
     */
    private static final class ProcessDownloadFailure extends IllegalStateException {

        private final FeishuDownloadCheckpoint checkpoint;

        private ProcessDownloadFailure(String message, FeishuDownloadCheckpoint checkpoint) {
            super(message);
            this.checkpoint = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
        }

        private FeishuDownloadCheckpoint checkpoint() {
            return checkpoint;
        }
    }
}
