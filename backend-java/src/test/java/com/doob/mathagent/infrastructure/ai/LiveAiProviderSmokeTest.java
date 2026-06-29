package com.doob.mathagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live smoke test for OpenAI-compatible chat providers.
 *
 * <p>Run with {@code -Dmath-agent.ai.live-smoke=true}. The test reads keys from process, user, and machine
 * environment variables and never logs secret values or raw model text.</p>
 */
class LiveAiProviderSmokeTest {

    private static final String LIVE_SMOKE_FLAG = "math-agent.ai.live-smoke";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    @Test
    void configuredProvidersReturnTinyChatCompletionWhenLiveSmokeIsEnabled() throws Exception {
        assumeTrue(Boolean.getBoolean(LIVE_SMOKE_FLAG), "Live AI smoke test is opt-in");
        List<LiveProvider> providers = configuredProviders();
        assertThat(providers).isNotEmpty();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        Map<String, String> results = new LinkedHashMap<>();
        for (LiveProvider provider : providers) {
            Instant started = Instant.now();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(provider.chatCompletionsUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(provider.requestBody()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
            results.put(provider.name(), provider.safeStatus(response.statusCode(), elapsedMillis));
            assertThat(response.statusCode())
                    .as(provider.name() + " should accept model " + provider.model())
                    .isBetween(200, 299);
            assertThat(response.body()).contains("\"choices\"");
        }

        System.out.println("live-ai-smoke=" + results);
    }

    /**
     * Builds live provider settings from environment variables without logging any secret.
     *
     * @return providers with usable credentials
     */
    private static List<LiveProvider> configuredProviders() {
        return List.of(
                        new LiveProvider(
                                "dashscope",
                                env("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                                env("DASHSCOPE_API_KEY", ""),
                                env("DASHSCOPE_CHAT_MODEL", "qwen3.6-flash")),
                        new LiveProvider(
                                "deepseek",
                                env("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                                env("DEEPSEEK_API_KEY", ""),
                                env("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash")),
                        new LiveProvider(
                                "ark",
                                env("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"),
                                env("ARK_API_KEY", ""),
                                env("ARK_CHAT_MODEL", "doubao-seed-2-0-lite-260428")),
                        new LiveProvider(
                                "openai",
                                env("OPENAI_BASE_URL", "https://api.openai.com"),
                                env("OPENAI_API_KEY", ""),
                                env("OPENAI_CHAT_MODEL", "gpt-5.4-mini")))
                .stream()
                .filter(LiveProvider::hasUsableCredentials)
                .toList();
    }

    /**
     * Reads an environment value from the current process first, then user and machine scopes on Windows.
     *
     * @param name environment variable name
     * @param fallback fallback value
     * @return resolved environment value
     */
    private static String env(String name, String fallback) {
        String processValue = System.getenv(name);
        if (hasText(processValue)) {
            return processValue.strip();
        }
        String userValue = readScopedEnvironment(name, "User");
        if (hasText(userValue)) {
            return userValue.strip();
        }
        String machineValue = readScopedEnvironment(name, "Machine");
        return hasText(machineValue) ? machineValue.strip() : fallback;
    }

    /**
     * Reads a Windows scoped environment variable through PowerShell so refreshed keys are visible to tests.
     *
     * @param name environment variable name
     * @param scope User or Machine
     * @return resolved value or an empty string
     */
    private static String readScopedEnvironment(String name, String scope) {
        try {
            Process process = new ProcessBuilder(
                            "powershell",
                            "-NoProfile",
                            "-Command",
                            "[Environment]::GetEnvironmentVariable('" + name + "', '" + scope + "')")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset())
                    .strip();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Returns whether a string contains non-whitespace text.
     *
     * @param value text value
     * @return true when present
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Live provider data used only by the smoke test.
     *
     * @param name provider name
     * @param baseUrl OpenAI-compatible base URL
     * @param apiKey secret API key
     * @param model chat model identifier
     */
    private record LiveProvider(String name, String baseUrl, String apiKey, String model) {

        /**
         * Returns whether the provider can be called.
         *
         * @return true when base URL, key, and model are all present
         */
        boolean hasUsableCredentials() {
            return hasText(baseUrl) && hasText(apiKey) && hasText(model);
        }

        /**
         * Builds the provider chat completions URL.
         *
         * @return chat completions endpoint
         */
        String chatCompletionsUrl() {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return normalized + "/chat/completions";
        }

        /**
         * Builds a tiny deterministic request body.
         *
         * @return JSON body
         */
        String requestBody() {
            return """
                    {"model":"%s","messages":[{"role":"user","content":"Reply with OK only."}],"temperature":0,"max_tokens":8}
                    """.formatted(json(model));
        }

        /**
         * Builds a status line safe for test output.
         *
         * @param statusCode HTTP status
         * @param elapsedMillis elapsed milliseconds
         * @return safe status summary
         */
        String safeStatus(int statusCode, long elapsedMillis) {
            return "model=" + model + ",status=" + statusCode + ",elapsedMs=" + elapsedMillis;
        }

        /**
         * Escapes JSON string content used in this test.
         *
         * @param value string value
         * @return escaped value
         */
        private static String json(String value) {
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }
    }
}
