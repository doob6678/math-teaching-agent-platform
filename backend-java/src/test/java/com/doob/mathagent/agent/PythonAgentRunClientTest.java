package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PythonAgentRunClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void signsRouteGrantsFromDeploymentAiConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.ai.route-grant-secret", "deployment-route-grant-key")
                .withProperty("math-agent.ai.route-grant-ttl-seconds", "120");

        String grant = new ProviderRouteGrantSigner(environment).sign(
                "run-001", "student_explanation", List.of(new ProviderRouteGrantSigner.ProviderRoute("openai", "model-a")));

        assertThat(grant).contains(".");
    }

    @Test
    void projectsVersionedPythonUsageAndUnknownCost() throws Exception {
        AgentRunClient.Result result = PythonAgentRunClient.project(OBJECT_MAPPER.readTree("""
                {
                  "contractVersion":"ai-run-v1",
                  "status":"COMPLETED",
                  "providerName":"openai",
                  "modelCode":"gpt-5.6-luna",
                  "message":"Python AI run completed.",
                  "generatedContent":"{\\"teacherExplanation\\":\\"空间向量讲解\\"}",
                  "actualUsage":{"promptTokens":11,"completionTokens":7,"totalTokens":18},
                  "actualCost":-1,
                  "costKnown":false
                }
                """));

        assertThat(result.providerName()).isEqualTo("openai");
        assertThat(result.modelCode()).isEqualTo("gpt-5.6-luna");
        assertThat(result.actualUsage().totalTokens()).isEqualTo(18);
        assertThat(result.actualCost()).isEqualTo(-1.0d);
        assertThat(result.costKnown()).isFalse();
    }

    @Test
    void sendsOnlyTheVersionedPythonContractToTheWorker() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ai-runs/sync", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            byte[] body = """
                    {"contractVersion":"ai-run-v1","status":"COMPLETED","providerName":"openai","modelCode":"gpt-5.6-luna","message":"completed","generatedContent":"{\\"teacherExplanation\\":\\"空间向量讲解\\"}","actualUsage":{"promptTokens":11,"completionTokens":7,"totalTokens":18},"actualCost":-1,"costKnown":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProviderCatalog catalog = providerCatalog();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("math-agent.python-agent.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("math-agent.python-agent.worker-key", "worker-contract-key")
                    .withProperty("math-agent.python-agent.route-grant-secret", "route-grant-test-key");
            PythonAgentRunClient client = new PythonAgentRunClient(
                    environment, catalog, new ProviderRouteGrantSigner(environment));

            AgentRunPlanResponse plan = coursewarePlan(catalog);
            AgentRunClient.Result result = client.execute(
                    "trace-contract-001",
                    new AgentRunExecuteRequest(plan, "解释空间向量夹角", List.of("PUBLIC_TEXTBOOK:vector-1"), false),
                    plan);

            assertThat(result.actualUsage().totalTokens()).isEqualTo(18);
            assertThat(authorization.get()).isEqualTo("Bearer worker-contract-key");
            assertThat(payload.get().path("contractVersion").asText()).isEqualTo("ai-run-v1");
            assertThat(payload.get().path("runId").asText()).isEqualTo("trace-contract-001");
            assertThat(payload.get().path("providerRoute").path("primary").path("name").asText()).isEqualTo("openai");
            assertThat(payload.get().path("providerRoute").path("primary").path("model").asText()).isEqualTo("gpt-5.6-luna");
            assertThat(payload.get().path("allowedTools").toString()).isEqualTo("[\"search_visible_resources\"]");
            assertThat(payload.get().path("evidenceRefs").toString()).isEqualTo("[\"PUBLIC_TEXTBOOK:vector-1\"]");
            assertThat(payload.get().toString()).doesNotContain("tenantId", "subjectId", "subjectType", "apiKey", "providerUrl");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsWorkerHttpFailuresToTheFacadeBoundary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ai-runs/sync", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            AiProviderCatalog catalog = providerCatalog();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("math-agent.python-agent.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("math-agent.python-agent.worker-key", "worker-contract-key")
                    .withProperty("math-agent.python-agent.route-grant-secret", "route-grant-test-key");
            PythonAgentRunClient client = new PythonAgentRunClient(
                    environment, catalog, new ProviderRouteGrantSigner(environment));

            AgentRunPlanResponse plan = coursewarePlan(catalog);
            assertThatThrownBy(() -> client.execute(
                            "trace-contract-002",
                            new AgentRunExecuteRequest(plan, "解释空间向量夹角", List.of(), false),
                            plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Python agent request failed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsWorkerErrorFramesToProviderUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/student-explanations/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(("id: 1\n"
                        + "event: error\n"
                        + "data: {\"runId\":\"stream-run-error\",\"status\":503,"
                        + "\"message\":\"all configured providers failed: openai:ValueError\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AiProviderCatalog catalog = providerCatalog();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("math-agent.python-agent.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("math-agent.python-agent.worker-key", "worker-contract-key")
                    .withProperty("math-agent.python-agent.route-grant-secret", "route-grant-test-key");
            PythonMigratedWorkloadClient client = new PythonMigratedWorkloadClient(
                    environment, catalog, new ProviderRouteGrantSigner(environment));

            assertThatThrownBy(() -> client.streamStudentExplanation(
                            "stream-run-error", "求函数定义域", List.of(), "", event -> { }))
                    .isInstanceOf(AiProviderUnavailableException.class)
                    .hasMessage("all configured providers failed: openai:ValueError");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsWorkerDeltasBeforeTheCompletedFrame() throws Exception {
        CountDownLatch deltaObserved = new CountDownLatch(1);
        AtomicBoolean completedWasWrittenBeforeDelta = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/student-explanations/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(("id: 1\n"
                        + "event: started\n"
                        + "data: {\"runId\":\"stream-run-1\"}\n\n"
                        + "id: 2\n"
                        + "event: delta\n"
                        + "data: {\"runId\":\"stream-run-1\",\"content\":\"第一段\","
                        + "\"providerName\":\"openai\",\"modelCode\":\"gpt-5.6-luna\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                stream.flush();
                try {
                    if (!deltaObserved.await(2, TimeUnit.SECONDS)) {
                        completedWasWrittenBeforeDelta.set(true);
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Interrupted while waiting for streamed delta", exception);
                }
                stream.write(("id: 3\n"
                        + "event: completed\n"
                        + "data: {\"runId\":\"stream-run-1\",\"conversationTitle\":\"定义域\","
                        + "\"cards\":[{\"cardKey\":\"domain\",\"title\":\"\",\"summary\":\"先看分母。\","
                        + "\"items\":[],\"sourceUris\":[],\"renderMode\":\"text\"}],"
                        + "\"usage\":{\"promptTokens\":2,\"completionTokens\":3,\"totalTokens\":5},"
                        + "\"providerName\":\"openai\",\"modelCode\":\"gpt-5.6-luna\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AiProviderCatalog catalog = providerCatalog();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("math-agent.python-agent.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("math-agent.python-agent.worker-key", "worker-contract-key")
                    .withProperty("math-agent.python-agent.route-grant-secret", "route-grant-test-key")
                    .withProperty("math-agent.python-agent.timeout-ms", "5000");
            PythonMigratedWorkloadClient client = new PythonMigratedWorkloadClient(
                    environment, catalog, new ProviderRouteGrantSigner(environment));
            AtomicReference<String> delta = new AtomicReference<>();

            PythonMigratedWorkloadClient.ExplanationResult result = client.streamStudentExplanation(
                    "stream-run-1", "求函数定义域", List.of(), "", event -> {
                        if ("delta".equals(event.eventName())) {
                            delta.set(event.content());
                            deltaObserved.countDown();
                        }
                    });

            assertThat(completedWasWrittenBeforeDelta).isFalse();
            assertThat(delta.get()).isEqualTo("第一段");
            assertThat(result.conversationTitle()).isEqualTo("定义域");
            assertThat(result.cards()).singleElement().extracting(
                    PythonMigratedWorkloadClient.ExplanationCard::summary).isEqualTo("先看分母。");
            assertThat(result.usage().totalTokens()).isEqualTo(5);
        } finally {
            server.stop(0);
        }
    }

    private static AgentRunPlanResponse coursewarePlan(AiProviderCatalog catalog) {
        return new AgentRunPlanService(catalog).plan(new AgentRunPlanRequest(
                        "CoursewareAgent", "courseware_generation", "teacher", 3000, 1600, false, true,
                        "medium", "normal", 2.5d, 0, true,
                        List.of("tool:courseware:generate", "tool:search:textbook"), List.of(),
                        List.of("PUBLIC_TEXTBOOK"), false),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setEnabled(true);
        properties.getOpenai().setChatModel("gpt-5.6-luna");
        return new AiProviderCatalog(properties);
    }

    @Test
    void rejectsIncompleteOrInconsistentPythonResults() throws Exception {
        assertThatThrownBy(() -> PythonAgentRunClient.project(OBJECT_MAPPER.readTree("""
                {"contractVersion":"ai-run-v1","status":"COMPLETED","providerName":"openai","modelCode":"gpt-5.6-luna","generatedContent":"x","actualUsage":{"promptTokens":9,"completionTokens":3,"totalTokens":3}}
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usage totals are inconsistent");
        assertThatThrownBy(() -> PythonAgentRunClient.project(OBJECT_MAPPER.readTree("""
                {"contractVersion":"ai-run-v1","status":"COMPLETED","providerName":"","modelCode":"gpt-5.6-luna","generatedContent":"x","actualUsage":{"promptTokens":1,"completionTokens":1,"totalTokens":2}}
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete result");
    }
}
