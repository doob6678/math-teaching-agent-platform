package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feishu discovery client backed by the verified Python URL downloader script in dry-run/list mode.
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.teacher.sync.feishu", name = "process-downloader-enabled", havingValue = "true")
public class ProcessTeacherFeishuDiscoveryClient implements TeacherFeishuDiscoveryClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 800L;
    private static final int MAX_ERROR_OUTPUT_CHARS = 4000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TeacherSourceSyncProperties properties;

    /**
     * Creates a process-backed Feishu discovery client.
     *
     * @param properties Feishu runtime paths and limits
     */
    public ProcessTeacherFeishuDiscoveryClient(TeacherSourceSyncProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs Feishu list/search discovery without downloading remote content.
     */
    @Override
    public TeacherFeishuDiscoveryResponse discover(TeacherFeishuDiscoveryQuery query) {
        validateConfiguredFiles();
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return runDiscovery(query, attempt);
            } catch (IllegalStateException exception) {
                lastFailure = exception;
                boolean retryable = isRetryable(exception);
                if (attempt == MAX_ATTEMPTS && retryable) {
                    throw new TeacherFeishuDiscoveryException(exception.getMessage(), true, exception);
                }
                if (attempt == MAX_ATTEMPTS || !retryable) {
                    throw exception;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Feishu discovery failed") : lastFailure;
    }

    /**
     * Runs one discovery attempt and parses the JSON summary produced by the Python script.
     */
    private TeacherFeishuDiscoveryResponse runDiscovery(TeacherFeishuDiscoveryQuery query, int attempt) {
        Path outputRoot = properties.feishuStagingRoot().toAbsolutePath().normalize();
        Path summaryPath = outputRoot.resolve("discovery-" + Instant.now().toEpochMilli() + "-attempt-" + attempt + ".json");
        Path processOutputPath = outputRoot.resolve(
                "discovery-output-" + Instant.now().toEpochMilli() + "-attempt-" + attempt + ".log");
        try {
            Files.createDirectories(outputRoot);
            List<String> command = command(query, summaryPath);
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(processOutputPath.toFile())
                    .start();
            boolean finished = process.waitFor(properties.feishuProcessTimeoutSeconds(), TimeUnit.SECONDS);
            String output = readProcessOutput(processOutputPath);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Feishu discovery timed out after " + properties.feishuProcessTimeoutSeconds()
                                + " seconds: " + safeProcessOutput(output));
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Feishu discovery failed with exit " + exitCode + ": " + safeProcessOutput(output));
            }
            String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
            return parseSummary(summary, query);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run Feishu discovery", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu discovery was interrupted", exception);
        }
    }

    /**
     * Builds the downloader script command for list or search discovery.
     */
    private List<String> command(TeacherFeishuDiscoveryQuery query, Path summaryPath) {
        List<String> command = new ArrayList<>();
        command.add("python");
        command.add(properties.feishuDownloaderScript().toString());
        command.add("--appkey-path");
        command.add(properties.feishuAppkeyPath().toString());
        command.add("--summary-path");
        command.add(summaryPath.toString());
        command.add("--quiet");
        String rootUrl = textOrDefault(query.rootUrl(), properties.feishuDefaultUrl());
        if (!rootUrl.isBlank()) {
            command.add("--root-url");
            command.add(rootUrl);
        }
        if ("search".equals(query.mode())) {
            command.add("--search-root");
            command.add(query.keyword());
            command.add("--dry-run");
            command.add("--max-depth");
            command.add(String.valueOf(query.maxDepth()));
        } else {
            command.add("--list-root");
            command.add("--list-depth");
            command.add(String.valueOf(query.listDepth()));
        }
        return command;
    }

    /**
     * Parses script summary JSON into the API response shape.
     */
    private static TeacherFeishuDiscoveryResponse parseSummary(String summary, TeacherFeishuDiscoveryQuery query) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(summary);
            List<TeacherFeishuDiscoveryResponse.Candidate> candidates = new ArrayList<>();
            for (JsonNode candidate : root.path("candidates")) {
                candidates.add(new TeacherFeishuDiscoveryResponse.Candidate(
                        text(candidate, "resource_type"),
                        text(candidate, "token"),
                        text(candidate, "name"),
                        text(candidate, "path"),
                        text(candidate, "url"),
                        candidate.path("depth").asInt(0),
                        candidate.path("downloadable").asBoolean(false)));
            }
            String mode = textOrDefault(text(root, "mode"), "search".equals(query.mode()) ? "search_root" : "list_root");
            String keyword = textOrDefault(text(root, "keyword"), query.keyword());
            String rootUrl = textOrDefault(root.path("root").path("url").asText(""), query.rootUrl());
            int depth = "search".equals(query.mode()) ? query.maxDepth() : query.listDepth();
            int count = root.path("count").asInt(candidates.size());
            return new TeacherFeishuDiscoveryResponse(
                    UUID.randomUUID().toString(),
                    mode,
                    rootUrl,
                    keyword,
                    depth,
                    count,
                    List.copyOf(candidates),
                    "ok",
                    "Found " + count + " Feishu candidates");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse Feishu discovery summary", exception);
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
     * Backs off briefly between retryable discovery attempts.
     */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu discovery retry was interrupted", exception);
        }
    }

    /**
     * Caps subprocess output so API messages cannot leak oversized logs.
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
     * Validates local files needed by the process discovery client.
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
     * Reads a JSON text field.
     */
    private static String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText("");
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
