package com.doob.mathagent.protocol;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineSeedService;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.resources.TextbookResourceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class McpJsonRpcServiceFixture {

    private McpJsonRpcServiceFixture() {
    }

    static McpJsonRpcService service(
            ProtocolDiscoveryService discoveryService,
            McpToolExecutionService toolExecutionService,
            McpClientRegistryProperties registryProperties) {
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        new KnowledgeGraphSpineSeedService(knowledgeStore, new KnowledgeGraphSpineProperties())
                .seedFromConfiguredSource();
        return new McpJsonRpcService(
                discoveryService,
                toolExecutionService,
                registryProperties,
                new TextbookResourceService(new TextbookCatalogReader()),
                new TextbookResourceProperties(defaultTextbookRoot()),
                new KnowledgeGraphSpineService(knowledgeStore));
    }

    static McpJsonRpcService service(
            ProtocolDiscoveryService discoveryService,
            McpToolExecutionService toolExecutionService,
            McpClientRegistryProperties registryProperties,
            TextbookResourceService textbookResourceService,
            TextbookResourceProperties textbookResourceProperties,
            KnowledgeGraphSpineService knowledgeGraphSpineService) {
        return new McpJsonRpcService(
                discoveryService,
                toolExecutionService,
                registryProperties,
                textbookResourceService,
                textbookResourceProperties,
                knowledgeGraphSpineService);
    }

    private static Path defaultTextbookRoot() {
        try {
            Path root = Files.createTempDirectory("math-agent-mcp-jsonrpc-textbooks");
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
            throw new IllegalStateException("Failed to create MCP JSON-RPC test textbook corpus", exception);
        }
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
