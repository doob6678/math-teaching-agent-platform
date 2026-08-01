package com.doob.mathagent.teacher.feishu;

import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
    private static final Pattern PROVIDER_CODE_PATTERN = Pattern.compile("[\\\"']code[\\\"']\\s*[:=]\\s*[\\\"']?([A-Za-z0-9_-]+)");
    private static final Pattern REQUIRED_SCOPE_PATTERN = Pattern.compile(
            "(?:required_scope|required scope|scope)\\s*[\\\"':= ]+([A-Za-z0-9_.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVILEGE_SUBJECT_PATTERN = Pattern.compile(
            "\\\"subject\\\"\\s*:\\s*\\\"([A-Za-z0-9_.:-]+)\\\"");
    private static final Pattern AUTHORIZATION_URL_PATTERN = Pattern.compile(
            "https?://[^\\s\\\"']+(?:authorize|authorization|permission|scope)[^\\s\\\"']*", Pattern.CASE_INSENSITIVE);

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
        return download(url, stagingRoot, maxFiles, "md", checkpoint);
    }

    @Override
    public FeishuDownloadResult downloadWithAccessToken(String url, Path stagingRoot, int maxFiles,
            String fileExtension, FeishuDownloadCheckpoint checkpoint, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return download(url, stagingRoot, maxFiles, fileExtension, checkpoint);
        try {
            Files.createDirectories(stagingRoot.toAbsolutePath().normalize());
            Path credentialFile = Files.createTempFile(stagingRoot.toAbsolutePath().normalize(), ".feishu-user-", ".credentials");
            Files.writeString(credentialFile, "ACCESS_TOKEN\n" + accessToken.strip() + "\n", StandardCharsets.UTF_8);
            try { return download(url, stagingRoot, maxFiles, fileExtension, checkpoint, credentialFile); }
            finally { Files.deleteIfExists(credentialFile); }
        } catch (IOException exception) { throw new IllegalStateException("Failed to prepare Feishu user credential", exception); }
    }

    @Override
    public FeishuDownloadResult download(
            String url,
            Path stagingRoot,
            int maxFiles,
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint) {
        return download(url, stagingRoot, maxFiles, fileExtension, checkpoint, null);
    }

    private FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles, String fileExtension,
            FeishuDownloadCheckpoint checkpoint, Path credentialFile) {
        validateConfiguredFiles(url, credentialFile);
        Path outputRoot = stagingRoot.toAbsolutePath().normalize();
        IllegalStateException lastFailure = null;
        String normalizedFileExtension = normalizeFileExtension(fileExtension);
        FeishuDownloadCheckpoint activeCheckpoint = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return runDownloader(url, outputRoot, maxFiles, normalizedFileExtension, activeCheckpoint, attempt, credentialFile);
            } catch (IllegalStateException exception) {
                lastFailure = exception;
                FeishuDownloadCheckpoint latestCheckpoint = latestCheckpoint(exception);
                if (latestCheckpoint.hasCursor()) {
                    activeCheckpoint = latestCheckpoint;
                }
                boolean retryable = isRetryable(exception);
                if (attempt == MAX_ATTEMPTS && retryable) {
                    throw providerException(exception, true, activeCheckpoint);
                }
                if (attempt == MAX_ATTEMPTS || !retryable) {
                    throw providerException(exception, false, activeCheckpoint);
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
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint,
            int attempt,
            Path credentialFile) {
        Path summaryPath = outputRoot.resolve("summary-" + Instant.now().toEpochMilli() + "-attempt-" + attempt + ".json");
        Path checkpointPath = outputRoot.resolve("resume-checkpoint-" + Instant.now().toEpochMilli()
                + "-attempt-" + attempt + ".json");
        Path processOutputPath = outputRoot.resolve("download-output-" + Instant.now().toEpochMilli()
                + "-attempt-" + attempt + ".log");
        try {
            Files.createDirectories(outputRoot);
            Process process = new ProcessBuilder(buildCommand(
                    url,
                    outputRoot,
                    summaryPath,
                    checkpointPath,
                    maxFiles,
                    fileExtension,
                    checkpoint, credentialFile))
                    .redirectErrorStream(true)
                    .redirectOutput(processOutputPath.toFile())
                    .start();
            boolean finished = process.waitFor(properties.feishuProcessTimeoutSeconds(), TimeUnit.SECONDS);
            String output = readProcessOutput(processOutputPath);
            if (!finished) {
                process.destroyForcibly();
                throw new ProcessDownloadFailure(
                        "Feishu downloader timed out after " + properties.feishuProcessTimeoutSeconds()
                                + " seconds: " + safeProcessOutput(output),
                        readCheckpoint(checkpointPath));
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new ProcessDownloadFailure(
                        "Feishu downloader failed with exit " + exitCode + ": " + safeProcessOutput(output),
                        readCheckpoint(checkpointPath));
            }
            String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
            FeishuDownloadResult result = parseSummary(summary, outputRoot);
            int failed = result.failed();
            if (failed > 0) {
                throw new ProcessDownloadFailure(
                        "Feishu downloader reported failed files: " + failed,
                        result.checkpoint().hasCursor() ? result.checkpoint() : readCheckpoint(checkpointPath));
            }
            return result;
        } catch (IOException exception) {
            // Preserve the local process-launch reason (for example a missing Python executable) without logging tokens.
            throw new IllegalStateException("Failed to run Feishu downloader: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu downloader was interrupted", exception);
        }
    }

    /**
     * Parses both legacy flat summaries and the current Python worker summary shape.
     */
    private static FeishuDownloadResult parseSummary(String summary, Path outputRoot) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(summary);
            JsonNode stats = root.path("stats");
            Path savedPath = Path.of(textOrDefault(root.path("saved_path").asText(""), outputRoot.toString()));
            int files = integerField(root, stats, "files");
            int skipped = integerField(root, stats, "skipped");
            int failed = integerField(root, stats, "failed");
            FeishuDownloadCheckpoint checkpoint = checkpointFromSummary(root.path("checkpoint"));
            String downloadedItemsJson = jsonArray(root.path("checkpoint").path("downloaded_items"));
            String failedItemsJson = jsonArray(root.path("failed_items"));
            JsonNode provider = root.path("provider");
            return new FeishuDownloadResult(
                    savedPath,
                    files,
                    skipped,
                    failed,
                    "Downloaded " + files + " Feishu files; skipped " + skipped,
                    checkpoint,
                    downloadedItemsJson,
                    failedItemsJson,
                    textOrDefault(provider.path("title").asText(""), null),
                    textOrDefault(provider.path("revision").asText(""), null),
                    jsonArray(root.path("discovered_items")),
                    jsonArray(root.path("changed_items")),
                    jsonArray(root.path("unchanged_items")));
        } catch (IOException exception) {
            Path savedPath = Path.of(textField(summary, SAVED_PATH_PATTERN, outputRoot.toString()));
            int files = intField(summary, FILES_PATTERN);
            int skipped = intField(summary, SKIPPED_PATTERN);
            int failed = intField(summary, FAILED_PATTERN);
            return new FeishuDownloadResult(
                    savedPath,
                    files,
                    skipped,
                    failed,
                    "Downloaded " + files + " Feishu files; skipped " + skipped,
                    FeishuDownloadCheckpoint.empty(),
                    "[]",
                    "[]",
                    null,
                    null,
                    "[]",
                    "[]",
                    "[]");
        }
    }

    /**
     * Reads a counter from the current nested stats object or the legacy flat root object.
     */
    private static int integerField(JsonNode root, JsonNode stats, String fieldName) {
        if (root.has(fieldName)) {
            return root.path(fieldName).asInt(0);
        }
        return stats.path(fieldName).asInt(0);
    }

    /**
     * Converts the Python summary checkpoint into the Java durable checkpoint protocol.
     */
    private static FeishuDownloadCheckpoint checkpointFromSummary(JsonNode checkpoint) {
        if (checkpoint == null || checkpoint.isMissingNode() || checkpoint.isNull()) {
            return FeishuDownloadCheckpoint.empty();
        }
        return new FeishuDownloadCheckpoint(
                checkpoint.path("current_folder_token").asText(""),
                checkpoint.path("current_path").asText(""),
                checkpoint.path("page_token").asText(""),
                jsonArray(checkpoint.path("visited_folder_tokens")),
                jsonArray(checkpoint.path("downloaded_items")));
    }

    /**
     * Serializes a JSON array node for checkpoint storage.
     */
    private static String jsonArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            return "[]";
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
            String fileExtension,
            FeishuDownloadCheckpoint checkpoint, Path credentialFile) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of(
                pythonExecutable(),
                properties.feishuDownloaderScript().toString(),
                "--url",
                url,
                "--appkey-path",
                credentialFile == null ? properties.feishuAppkeyPath().toString() : credentialFile.toString(),
                "--output-dir",
                outputRoot.toString(),
                "--summary-path",
                summaryPath.toString(),
                "--max-files",
                String.valueOf(maxFiles),
                "--file-extension",
                normalizeFileExtension(fileExtension),
                "--quiet"));
        command.add("--manifest-path");
        command.add(outputRoot.resolve(".manifests").resolve(
                com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity.hash(url) + ".json").toString());
        FeishuDownloadCheckpoint normalized = checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint;
        if (normalized.hasCursor()) {
            Files.writeString(checkpointPath, checkpointJson(normalized), StandardCharsets.UTF_8);
            command.add("--resume-checkpoint-path");
            command.add(checkpointPath.toString());
        }
        return command;
    }

    /**
     * Resolves the interpreter across Windows development and Linux containers without baking a host-specific path
     * into the sync job. Deployments can override the executable when Python lives outside PATH.
     */
    private static String pythonExecutable() {
        String configured = System.getenv("MATH_AGENT_PYTHON_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "python" : "python3";
    }

    /**
     * Normalizes the requested native Feishu export format.
     */
    private static String normalizeFileExtension(String value) {
        String normalized = value == null || value.isBlank() ? "md" : value.strip().toLowerCase(Locale.ROOT);
        if ("md".equals(normalized) || "docx".equals(normalized) || "pdf".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Feishu export format: " + value);
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
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
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
                || message.contains("504")
                // Feishu uses HTTP 429 for per-app/global request concurrency and frequency limits.  The durable
                // downloader checkpoint means the next attempt resumes from the completed folder cursor instead of
                // restarting the whole tree and immediately hitting the same limit again.
                || message.contains("429")
                || message.contains("too many requests")
                || message.contains("rate limit");
    }

    /**
     * Converts raw real-provider output to bounded structured data. No scope or authorization URL is synthesized:
     * absent provider fields remain absent so the frontend can accurately tell an administrator what was reported.
     */
    private static TeacherFeishuDownloadException providerException(
            IllegalStateException exception,
            boolean retryable,
            FeishuDownloadCheckpoint checkpoint) {
        String message = textOrDefault(exception.getMessage(), "Feishu downloader failed");
        Matcher codeMatcher = PROVIDER_CODE_PATTERN.matcher(message);
        String providerCode = null;
        // A Markdown fallback can report an initial endpoint validation failure followed by the decisive raw-content
        // provider result. Keep the final code, which is the failure that actually stopped the download.
        while (codeMatcher.find()) {
            providerCode = codeMatcher.group(1);
        }
        Matcher scopeMatcher = REQUIRED_SCOPE_PATTERN.matcher(message);
        List<String> scopes = new ArrayList<>();
        while (scopeMatcher.find()) {
            String scope = scopeMatcher.group(1);
            if (scope != null && !scope.isBlank() && !scopes.contains(scope)) {
                scopes.add(scope);
            }
        }
        Matcher privilegeMatcher = PRIVILEGE_SUBJECT_PATTERN.matcher(message);
        while (privilegeMatcher.find()) {
            String scope = privilegeMatcher.group(1);
            if (!scope.isBlank() && !scopes.contains(scope)) {
                scopes.add(scope);
            }
        }
        Matcher urlMatcher = AUTHORIZATION_URL_PATTERN.matcher(message);
        String authorizationUrl = urlMatcher.find() ? urlMatcher.group() : null;
        return new TeacherFeishuDownloadException(
                message,
                retryable,
                exception,
                checkpoint,
                new TeacherSourceSyncFailureResponse(providerCode, retryable, scopes, authorizationUrl));
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
     * Reads bounded subprocess output captured outside the pipe to avoid blocking on network hangs.
     */
    private static String readProcessOutput(Path processOutputPath) throws IOException {
        return Files.isRegularFile(processOutputPath)
                ? new String(Files.readAllBytes(processOutputPath), StandardCharsets.UTF_8)
                : "";
    }

    /**
     * Validates local files needed by the process downloader.
     */
    private void validateConfiguredFiles(String sourceUrl, Path credentialFile) {
        if (!Files.isRegularFile(properties.feishuDownloaderScript())) {
            throw new IllegalStateException("Feishu downloader script not found");
        }
        if (credentialFile == null && !properties.credentialsConfigured()) {
            // Missing app credentials is an authorization recovery state, not a terminal parse failure.  The original
            // administrator-supplied root is safe to show as the re-login destination and contains no browser cookie.
            throw new TeacherFeishuDownloadException(
                    "Feishu authorization is required: APPKEY credentials are not configured",
                    false,
                    null,
                    FeishuDownloadCheckpoint.empty(),
                    new TeacherSourceSyncFailureResponse("AUTH_REQUIRED", false, List.of(), sourceUrl));
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
