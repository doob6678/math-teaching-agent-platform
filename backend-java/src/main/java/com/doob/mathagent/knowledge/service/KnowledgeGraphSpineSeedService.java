package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Imports the curated display-safe high-school math knowledge graph spine.
 */
@Service
public class KnowledgeGraphSpineSeedService {

    private static final String SOURCE_TAG = "display_spine_v0.2";
    private static final String KNOWLEDGE_PREFIX = "- \u77e5\u8bc6\u70b9\uff1a";
    private static final String ROUTING_ALIAS_PREFIX = "- \u68c0\u7d22\u522b\u540d\uff1a";
    private static final String RETRIEVAL_SIGNAL_PREFIX = "- \u9898\u5e72\u4fe1\u53f7\uff1a";
    private static final String FEISHU_DIRECTORY_PREFIX = "- \u98de\u4e66\u76ee\u5f55\uff1a";
    private static final String EXCLUSION_TERM_PREFIX = "- \u6613\u6df7\u6392\u9664\uff1a";
    private static final String METHOD_PREFIX = "- \u9898\u578b\u65b9\u6cd5\uff1a";

    private final KnowledgeQuestionBankStore store;
    private final KnowledgeGraphSpineProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Creates a production seed service.
     *
     * @param store knowledge graph store
     * @param properties graph spine seed configuration
     */
    @Autowired
    public KnowledgeGraphSpineSeedService(
            KnowledgeQuestionBankStore store,
            KnowledgeGraphSpineProperties properties) {
        this(store, properties, new DefaultResourceLoader());
    }

    /**
     * Creates a testable seed service.
     *
     * @param store knowledge graph store
     * @param properties graph spine seed configuration
     * @param resourceLoader loader used to read Markdown seed data
     */
    public KnowledgeGraphSpineSeedService(
            KnowledgeQuestionBankStore store,
            KnowledgeGraphSpineProperties properties,
            ResourceLoader resourceLoader) {
        this.store = store;
        this.properties = properties;
        this.resourceLoader = resourceLoader == null ? new DefaultResourceLoader() : resourceLoader;
    }

    /**
     * Seeds the configured graph spine when it is not already visible.
     *
     * @return seed result with row counts
     */
    public KnowledgeGraphSpineSeedResult seedIfEnabled() {
        if (!properties.isSeedEnabled()) {
            return new KnowledgeGraphSpineSeedResult(false, 0, 0, "disabled");
        }
        return seedFromConfiguredSource();
    }

    /**
     * Reads the configured Markdown source and writes deterministic graph rows.
     */
    public KnowledgeGraphSpineSeedResult seedFromConfiguredSource() {
        try {
            Resource resource = resourceLoader.getResource(properties.getSourceLocation());
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            ParsedSpine parsed = parse(markdown, Math.max(1, properties.getMethodNodeLimit()));
            write(parsed);
            return new KnowledgeGraphSpineSeedResult(
                    true,
                    parsed.nodes().size(),
                    parsed.relations().size(),
                    properties.getSourceLocation());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read knowledge graph spine source", exception);
        }
    }

    /**
     * Parses modules, topics, method nodes, and core relation edges from Markdown.
     */
    private ParsedSpine parse(String markdown, int methodNodeLimit) {
        Map<String, SeedNode> nodes = new LinkedHashMap<>();
        List<SeedRelation> relations = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        String currentModuleId = "";
        String currentModuleName = "";
        String currentTopicId = "";
        String currentTopicName = "";
        boolean readingCoreRelations = false;
        List<String> coreRelationLines = new ArrayList<>();
        int methodCount = 0;

        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                continue;
            }
            if ("## \u6838\u5fc3\u5173\u7cfb\u8fb9".equals(line)) {
                readingCoreRelations = true;
                continue;
            }
            if (readingCoreRelations && line.startsWith("- ") && line.contains(" -> ")) {
                coreRelationLines.add(line.substring(2));
                continue;
            }
            // Only numbered level-two headings are modules. Version notes and relation headings are metadata, never nodes.
            if (line.matches("^##\\s+\\d+\\.\\s+.+$")) {
                readingCoreRelations = false;
                String moduleName = stripNumberPrefix(line.substring(3));
                currentModuleName = moduleName;
                currentModuleId = nodeId("module", moduleName);
                currentTopicId = "";
                currentTopicName = "";
                nodes.put(currentModuleId, new SeedNode(
                        currentModuleId,
                        "MODULE",
                        moduleName,
                        moduleName,
                        "\u4e00\u7ea7\u6a21\u5757\uff1b\u6559\u6750\u7ae0\u8282\u4e3b\u5e72"));
                aliases.put(moduleName, currentModuleId);
                continue;
            }
            if (line.startsWith("### ") && !currentModuleId.isBlank()) {
                String topicName = stripNumberPrefix(line.substring(4));
                currentTopicName = topicName;
                currentTopicId = nodeId("topic", currentModuleName + "/" + topicName);
                nodes.put(currentTopicId, new SeedNode(
                        currentTopicId,
                        "TOPIC",
                        topicName,
                        currentModuleName + "/" + topicName,
                        "\u4e8c\u7ea7\u77e5\u8bc6\u70b9"));
                aliases.put(topicName, currentTopicId);
                relations.add(new SeedRelation(
                        relationId("contains", currentModuleId, currentTopicId),
                        currentModuleId,
                        currentTopicId,
                        "CONTAINS_TOPIC",
                        currentModuleName + " \u5305\u542b " + topicName));
                continue;
            }
            if (line.startsWith(KNOWLEDGE_PREFIX) && !currentTopicId.isBlank()) {
                appendTopicMetadata(nodes, currentTopicId, "\u77e5\u8bc6\u70b9", line.substring(KNOWLEDGE_PREFIX.length()).strip());
                continue;
            }
            if (line.startsWith(ROUTING_ALIAS_PREFIX) && !currentTopicId.isBlank()) {
                String aliasesForTopic = line.substring(ROUTING_ALIAS_PREFIX.length()).strip();
                appendTopicMetadata(nodes, currentTopicId, "routingAliases", aliasesForTopic);
                for (String alias : splitChineseList(aliasesForTopic)) {
                    aliases.put(alias, currentTopicId);
                }
                continue;
            }
            if (line.startsWith(RETRIEVAL_SIGNAL_PREFIX) && !currentTopicId.isBlank()) {
                appendTopicMetadata(nodes, currentTopicId, "retrievalSignals",
                        line.substring(RETRIEVAL_SIGNAL_PREFIX.length()).strip());
                continue;
            }
            if (line.startsWith(FEISHU_DIRECTORY_PREFIX) && !currentTopicId.isBlank()) {
                appendTopicMetadata(nodes, currentTopicId, "feishuDirectories",
                        line.substring(FEISHU_DIRECTORY_PREFIX.length()).strip());
                continue;
            }
            if (line.startsWith(EXCLUSION_TERM_PREFIX) && !currentTopicId.isBlank()) {
                appendTopicMetadata(nodes, currentTopicId, "exclusionTerms",
                        line.substring(EXCLUSION_TERM_PREFIX.length()).strip());
                continue;
            }
            if (line.startsWith(METHOD_PREFIX) && !currentTopicId.isBlank() && methodCount < methodNodeLimit) {
                String methods = line.substring(METHOD_PREFIX.length()).strip();
                for (String method : splitChineseList(methods)) {
                    if (methodCount >= methodNodeLimit) {
                        break;
                    }
                    String methodId = nodeId("method", currentModuleName + "/" + currentTopicName + "/" + method);
                    nodes.put(methodId, new SeedNode(
                            methodId,
                            "METHOD",
                            method,
                            currentModuleName + "/" + currentTopicName + "/\u9898\u578b\u65b9\u6cd5",
                            "\u9ad8\u9891\u9898\u578b\u65b9\u6cd5\uff1b\u6765\u6e90\uff1a\u98de\u4e66\u8001\u5e08\u6c89\u6dc0\uff1b\u7236\u77e5\u8bc6\u70b9\uff1a" + currentTopicName));
                    aliases.put(method, methodId);
                    relations.add(new SeedRelation(
                            relationId("method", currentTopicId, methodId),
                            currentTopicId,
                        methodId,
                        "METHOD_FOR",
                        method + " \u7528\u4e8e " + currentTopicName));
                    methodCount++;
                }
            }
        }
        for (String relationLine : coreRelationLines) {
            coreRelation(relationLine, aliases).ifPresent(relations::add);
        }
        return new ParsedSpine(List.copyOf(nodes.values()), List.copyOf(relations));
    }

    /**
     * Writes parsed nodes first, then relations so database foreign keys remain valid.
     */
    private void write(ParsedSpine parsed) {
        String tenantId = textOrDefault(properties.getTenantId(), RequestSubject.DEFAULT_TENANT_ID);
        String scope = textOrDefault(properties.getPermissionScope(), "MATH_VIP").toUpperCase(Locale.ROOT);
        for (SeedNode node : parsed.nodes()) {
            store.saveKnowledgePoint(new KnowledgePointRecord(
                    node.id(),
                    tenantId,
                    null,
                    scope,
                    node.name(),
                    node.chapterPath(),
                    "active",
                    SOURCE_TAG + "; nodeType=" + node.nodeType() + "; " + node.sourceSummary()));
        }
        for (SeedRelation relation : parsed.relations()) {
            store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                    relation.id(),
                    tenantId,
                    relation.sourceId(),
                    relation.targetId(),
                    relation.relationType(),
                    SOURCE_TAG + "; " + relation.evidenceSummary(),
                    "active"));
        }
    }

    /**
     * Converts one source relation line into a relation when both endpoint aliases are known.
     */
    private static Optional<SeedRelation> coreRelation(String value, Map<String, String> aliases) {
        String relationType = "PREREQUISITE_FOR";
        String evidence = "";
        String endpoints = value;
        String[] explicitParts = value.split("\\|", 3);
        if (explicitParts.length == 3) {
            relationType = explicitParts[0].strip().toUpperCase(Locale.ROOT);
            endpoints = explicitParts[1].strip();
            evidence = explicitParts[2].strip();
        }
        String[] parts = endpoints.split(" -> ", 2);
        if (parts.length != 2 || relationType.isBlank()) {
            return Optional.empty();
        }
        String sourceName = parts[0].strip();
        String targetName = parts[1].strip();
        String sourceId = aliases.get(sourceName);
        String targetId = aliases.get(targetName);
        if (sourceId == null || targetId == null) {
            return Optional.empty();
        }
        return Optional.of(new SeedRelation(
                relationId("core:" + relationType, sourceId, targetId),
                sourceId,
                targetId,
                relationType,
                evidence.isBlank() ? sourceName + " -> " + targetName : evidence));
    }

    /**
     * Adds a source-maintained routing value to a topic summary. The retrieval service consumes these fields directly,
     * which keeps display names, Feishu terminology, and question-language variants in one reviewable graph source.
     */
    private static void appendTopicMetadata(
            Map<String, SeedNode> nodes,
            String topicId,
            String key,
            String value) {
        if (value.isBlank()) {
            return;
        }
        SeedNode previous = nodes.get(topicId);
        nodes.put(topicId, new SeedNode(
                previous.id(),
                previous.nodeType(),
                previous.name(),
                previous.chapterPath(),
                previous.sourceSummary() + "；" + key + "=" + value));
    }

    /**
     * Removes numeric section prefixes such as "1.2 ".
     */
    private static String stripNumberPrefix(String value) {
        return value.strip().replaceFirst("^\\d+(?:\\.\\d+)*\\.?\\s*", "").strip();
    }

    /**
     * Splits Chinese list text on the ideographic comma.
     */
    private static List<String> splitChineseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("\u3001")) {
            String normalized = part.strip();
            if (!normalized.isBlank()) {
                parts.add(normalized);
            }
        }
        return parts;
    }

    /**
     * Creates a deterministic UUID for a graph node.
     */
    private static String nodeId(String type, String key) {
        return uuid("math-agent:knowledge-spine:v0.1:node:" + type + ":" + key);
    }

    /**
     * Creates a deterministic UUID for a graph relation.
     */
    private static String relationId(String type, String sourceId, String targetId) {
        return uuid("math-agent:knowledge-spine:v0.1:relation:" + type + ":" + sourceId + ":" + targetId);
    }

    /**
     * Creates a stable name-based UUID string.
     */
    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Returns stripped text or a default value.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Parsed graph before persistence.
     */
    private record ParsedSpine(List<SeedNode> nodes, List<SeedRelation> relations) {
    }

    /**
     * Seed node extracted from the curated Markdown source.
     */
    private record SeedNode(String id, String nodeType, String name, String chapterPath, String sourceSummary) {
    }

    /**
     * Seed relation extracted from the curated Markdown source.
     */
    private record SeedRelation(String id, String sourceId, String targetId, String relationType, String evidenceSummary) {
    }

    /**
     * Result returned after one seed pass.
     */
    public record KnowledgeGraphSpineSeedResult(boolean executed, int nodeCount, int relationCount, String source) {
    }
}
