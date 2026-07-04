package com.doob.mathagent.protocol;

import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teacher.TeacherResourceServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.core.task.SyncTaskExecutor;

final class McpToolExecutionServiceFixture {

    private McpToolExecutionServiceFixture() {
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties) {
        return service(registryProperties, textbookRetrievalService, textbookResourceProperties, null, null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService) {
        return service(registryProperties, textbookRetrievalService, textbookResourceProperties, teacherResourceBlockSearchService, null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService) {
        return service(
                registryProperties,
                textbookRetrievalService,
                textbookResourceProperties,
                teacherResourceBlockSearchService,
                agentTraceQueryService,
                null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService,
            AgentRunPlanService agentRunPlanService) {
        return service(
                registryProperties,
                textbookRetrievalService,
                textbookResourceProperties,
                teacherResourceBlockSearchService,
                agentTraceQueryService,
                agentRunPlanService,
                null,
                null,
                null,
                null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService,
            AgentRunPlanService agentRunPlanService,
            TeacherFeishuDiscoveryService teacherFeishuDiscoveryService,
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService teacherSourceSyncJobService,
            TeacherSourceSyncExecutionService teacherSourceSyncExecutionService) {
        return service(
                registryProperties,
                textbookRetrievalService,
                textbookResourceProperties,
                teacherResourceBlockSearchService,
                agentTraceQueryService,
                agentRunPlanService,
                teacherFeishuDiscoveryService,
                teacherResourceService,
                teacherSourceSyncJobService,
                teacherSourceSyncExecutionService,
                null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService,
            AgentRunPlanService agentRunPlanService,
            TeacherFeishuDiscoveryService teacherFeishuDiscoveryService,
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService teacherSourceSyncJobService,
            TeacherSourceSyncExecutionService teacherSourceSyncExecutionService,
            MultiAgentWritingService multiAgentWritingService) {
        return service(
                registryProperties,
                textbookRetrievalService,
                textbookResourceProperties,
                teacherResourceBlockSearchService,
                agentTraceQueryService,
                agentRunPlanService,
                teacherFeishuDiscoveryService,
                teacherResourceService,
                teacherSourceSyncJobService,
                teacherSourceSyncExecutionService,
                multiAgentWritingService,
                null);
    }

    static McpToolExecutionService service(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService,
            AgentRunPlanService agentRunPlanService,
            TeacherFeishuDiscoveryService teacherFeishuDiscoveryService,
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService teacherSourceSyncJobService,
            TeacherSourceSyncExecutionService teacherSourceSyncExecutionService,
            MultiAgentWritingService multiAgentWritingService,
            MultiAgentWritingArtifactExportService multiAgentWritingArtifactExportService) {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TextbookResourceProperties resolvedTextbookProperties = textbookResourceProperties == null
                ? new TextbookResourceProperties(defaultTextbookRoot())
                : textbookResourceProperties;
        TeacherResourceService resolvedResourceService = teacherResourceService == null
                ? TeacherResourceServiceFixture.service(resourceStore)
                : teacherResourceService;
        TeacherSourceSyncJobService resolvedJobService = teacherSourceSyncJobService == null
                ? new TeacherSourceSyncJobService(resourceStore, jobStore)
                : teacherSourceSyncJobService;
        TeacherSourceSyncExecutionService resolvedExecutionService = teacherSourceSyncExecutionService == null
                ? new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        defaultDownloadClient(),
                        defaultSyncProperties(),
                        checkpointStore,
                        TestVectorIndexService.successful(resourceStore, blockStore))
                : teacherSourceSyncExecutionService;
        MultiAgentWritingService resolvedWritingService = multiAgentWritingService == null
                ? disabledWritingService()
                : multiAgentWritingService;
        return new McpToolExecutionService(
                registryProperties,
                textbookRetrievalService == null ? defaultTextbookRetrievalService() : textbookRetrievalService,
                resolvedTextbookProperties,
                teacherResourceBlockSearchService == null
                        ? com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore)
                        : teacherResourceBlockSearchService,
                agentTraceQueryService == null ? new AgentTraceQueryService(new InMemoryAgentTraceStore()) : agentTraceQueryService,
                agentRunPlanService == null ? new AgentRunPlanService(defaultProviderCatalog()) : agentRunPlanService,
                teacherFeishuDiscoveryService == null ? defaultDiscoveryService() : teacherFeishuDiscoveryService,
                resolvedResourceService,
                resolvedJobService,
                resolvedExecutionService,
                resolvedWritingService,
                multiAgentWritingArtifactExportService == null
                        ? new MultiAgentWritingArtifactExportService(resolvedWritingService, 30)
                        : multiAgentWritingArtifactExportService);
    }

    private static TextbookRetrievalService defaultTextbookRetrievalService() {
        return com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
    }

    private static Path defaultTextbookRoot() {
        try {
            Path root = Files.createTempDirectory("math-agent-mcp-textbooks");
            Path bookRoot = root.resolve("book_default");
            Files.createDirectories(bookRoot.resolve("jsonl"));
            Files.writeString(root.resolve("catalog.jsonl"), """
                    {"doc_id":"book_default","book_name":"Default Textbook","volume":"test","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                    """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
            Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                    {"chunk_id":"default_p001","doc_id":"book_default","book_name":"Default Textbook","volume":"test","chapter_path":["Functions"],"page_no":1,"printed_page_no":"1","chunk_type":"page_summary","section_title":"Function","text":"function derivative vector probability","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p001.png"}
                    """);
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create MCP test textbook corpus", exception);
        }
    }

    private static TeacherFeishuDiscoveryService defaultDiscoveryService() {
        return new TeacherFeishuDiscoveryService(query -> new TeacherFeishuDiscoveryResponse(
                "query-test",
                query.mode(),
                query.rootUrl(),
                query.keyword(),
                query.maxDepth(),
                0,
                List.of(),
                "ok",
                "No test Feishu candidates"));
    }

    private static TeacherFeishuDownloadClient defaultDownloadClient() {
        return new TeacherFeishuDownloadClient() {
            @Override
            public FeishuDownloadResult download(
                    String url,
                    Path stagingRoot,
                    int maxFiles,
                    String fileExtension,
                    FeishuDownloadCheckpoint checkpoint) {
                try {
                    Files.createDirectories(stagingRoot);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to create MCP test staging directory", exception);
                }
                return new FeishuDownloadResult(
                        stagingRoot,
                        0,
                        0,
                        0,
                        "No test files downloaded",
                        FeishuDownloadCheckpoint.empty(),
                        "[]",
                        "[]");
            }
        };
    }

    private static TeacherSourceSyncProperties defaultSyncProperties() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "math-agent-mcp-sync-fixture");
        return new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/root-token",
                root.resolve("download_feishu_url.py"),
                root.resolve("APPKEY.md"),
                root.resolve("staging"),
                1);
    }

    private static MultiAgentWritingService disabledWritingService() {
        AiProviderCatalog catalog = defaultProviderCatalog();
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        new InMemoryAgentTraceStore(),
                        new InMemoryAgentConcurrencyGuard(),
                        request -> {
                            throw new IllegalStateException("Test must provide AiChatGateway before using writing tools");
                        },
                        catalog,
                        Clock.systemUTC()),
                new InMemoryMultiAgentWritingWorkflowStore(),
                new SyncTaskExecutor());
    }

    private static AiProviderCatalog defaultProviderCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
