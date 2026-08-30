package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentToolBrokerReadRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerAssetRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerSearchRequest;
import com.doob.mathagent.agent.dto.HandoutContextRequest;
import com.doob.mathagent.agent.dto.HandoutDocumentPageReadRequest;
import com.doob.mathagent.agent.dto.HandoutDocumentReadRequest;
import com.doob.mathagent.agent.dto.HandoutCanonicalQuestionReadRequest;
import com.doob.mathagent.agent.dto.HandoutDocumentSearchRequest;
import com.doob.mathagent.agent.dto.HandoutTeacherResourceSearchRequest;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.HandoutDocumentImageRewriter;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teaching.service.TeachingTaskStore;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.resources.TextbookAuthorizedBlockReader;
import com.doob.mathagent.retrieval.CanonicalMathPaperAuthorizedBlockReader;
import com.doob.mathagent.retrieval.CanonicalMathPaperAssetService;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Base64;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal-only bridge between the Python Agent runtime and protected Java domain services.
 *
 * <p>The shared worker key authenticates the local worker process. The backend keeps the request contract limited to
 * the already-resolved tenant/user scope and opaque resource identifiers; browser-provided authorization values are not used.</p>
 */
@RestController
@RequestMapping("/internal/agent-tools/v1")
public class AgentToolBrokerController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolBrokerController.class);
    /** Read in bounded chunks so a malformed or oversized authorized asset cannot exhaust worker-process memory. */
    private static final int ASSET_READ_BUFFER_BYTES = 8 * 1024;
    private static final int MAX_HANDOUT_DOCUMENT_BLOCKS = 80;
    private static final int MAX_HANDOUT_DOCUMENT_CHARS = 48_000;
    private static final int MAX_HANDOUT_SEARCH_BLOCKS = 16;
    private static final int MAX_HANDOUT_SEARCH_CHARS = 16_000;
    private final TeacherResourceBlockSearchService resourceSearchService;
    private final TeacherResourceAssetService assetService;
    private final TextbookAuthorizedBlockReader textbookBlockReader;
    private final CanonicalMathPaperAuthorizedBlockReader canonicalPaperBlockReader;
    private final CanonicalMathPaperAssetService canonicalAssetService;
    private final Environment environment;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final TeachingTaskStore teachingTaskStore;
    private final AgentTraceStore agentTraceStore;
    private final ObjectMapper objectMapper;
    private final HandoutDocumentImageRewriter imageRewriter;

    @Autowired
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            TextbookAuthorizedBlockReader textbookBlockReader,
            CanonicalMathPaperAuthorizedBlockReader canonicalPaperBlockReader,
            CanonicalMathPaperAssetService canonicalAssetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore,
            ObjectMapper objectMapper) {
        this.resourceSearchService = resourceSearchService;
        this.assetService = assetService;
        this.textbookBlockReader = textbookBlockReader;
        this.canonicalPaperBlockReader = canonicalPaperBlockReader;
        this.canonicalAssetService = canonicalAssetService;
        this.environment = environment;
        this.workflowStore = workflowStore;
        this.teachingTaskStore = teachingTaskStore;
        this.agentTraceStore = agentTraceStore;
        this.objectMapper = objectMapper;
        this.imageRewriter = new HandoutDocumentImageRewriter(
                environment.getProperty("math-agent.handout.source-image-label-prefix", "source-image"),
                environment.getProperty("math-agent.handout.max-images", Integer.class, 12));
    }

    /** Keeps direct tests source-compatible while production also injects canonical publication access. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            TextbookAuthorizedBlockReader textbookBlockReader,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this(resourceSearchService, assetService, textbookBlockReader, null, null, environment, workflowStore,
                teachingTaskStore, agentTraceStore, new ObjectMapper());
    }

    /** Keeps focused broker tests source-compatible. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore) {
        this(resourceSearchService, assetService, null, null, null, environment, workflowStore, null, null, new ObjectMapper());
    }

    /** Compatibility constructor for direct tests with durable teaching-task storage. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this(resourceSearchService, assetService, null, null, null, environment, workflowStore, teachingTaskStore,
                agentTraceStore, new ObjectMapper());
    }

    /** Compatibility constructor for focused broker tests that do not load workflow persistence. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment) {
        this(resourceSearchService, assetService, null, null, null, environment, null, null, null, new ObjectMapper());
    }

    /**
     * Fetches one compact evidence snapshot for the complete Python graph.  Identity is derived from the durable
     * Java workflow row keyed by runId; Python cannot choose tenantId, role, or subjectId in this batch contract.
     */
    @PostMapping("/handout-context")
    public Map<String, Object> handoutContext(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutContextRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        // A new run has no Java-preselected references. Return an empty, run-bound context so the Python plan node
        // can decide whether to invoke the teacher-resource search broker; nonempty refs still require exact grants.
        List<TeachingEvidence> authorized = request.evidenceRefs() == null
                || request.evidenceRefs().isEmpty()
                ? List.of()
                : authorizedHandoutEvidence(request.runId(), request.evidenceRefs());
        List<Map<String, Object>> items = authorized.stream()
                // A stale source is excluded only when it previously advertised an expandable document capability.
                .filter(evidence -> isContextEvidenceVisible(evidence, subject))
                .limit(request.limit())
                .map(evidence -> contextEvidenceItem(request.runId(), evidence, subject))
                .toList();
        auditHandoutInspection(request.runId(), "context", "", items.size());
        return Map.of("runId", request.runId(), "items", items);
    }

    private Map<String, Object> contextEvidenceItem(String runId, TeachingEvidence evidence, RequestSubject subject) {
        AiVisibleImageContext imageContext = rewriteAiVisibleExcerpt(runId, evidence, evidence.snippet(), subject);
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("ref", evidenceRef(runId, evidence));
        item.put("title", evidence.sourceTitle());
        item.put("documentName", evidence.sourceTitle());
        item.put("documentRef", hasDocumentReferenceCandidate(evidence) && isContextEvidenceVisible(evidence, subject)
                ? documentRef(runId, evidence.sourceDocumentId()) : "");
        item.put("excerpt", imageContext.text());
        item.put("sourceRelativePath", evidence.sourcePath());
        item.put("imageRefs", imageContext.imageRefs());
        return Map.copyOf(item);
    }

    /**
     * Applies the same authorized image rewrite to direct retrieval and deep reads. A vector excerpt may only expose
     * an image row when its persisted source block supplies the matching logical path; otherwise the row is removed
     * and the model must choose the already-available bounded document read.
     */
    private AiVisibleImageContext rewriteAiVisibleExcerpt(
            String runId, TeachingEvidence evidence, String excerpt, RequestSubject subject) {
        String source = compactEvidence(excerpt, "");
        TeacherDocumentBlockResponse block = sourceBlockForEvidence(evidence, subject);
        if (block == null || block.imageRefs() == null || block.imageRefs().isBlank()) {
            return new AiVisibleImageContext(stripUnboundMarkdownImages(source), List.of());
        }
        List<TeacherDocumentBlockResponse> rewritten = imageRewriter.rewrite(runId, List.of(block), subject,
                assetService == null ? null : (documentId, logicalPath, viewer) -> assetService
                        .openVisibleLogicalAsset(documentId, logicalPath, viewer).isPresent());
        TeacherDocumentBlockResponse verified = rewritten.getFirst();
        String verifiedText = compactEvidence(verified.rawText(), verified.normalizedText());
        String visibleText = source.isBlank() ? verifiedText : rewriteExcerptRows(source, verifiedText);
        if (!source.isBlank() && markdownImageMatcher(visibleText).find()) {
            return new AiVisibleImageContext(visibleText, parseImageRefs(verified.imageRefs()));
        }
        String imageRows = markdownImageMatcher(verifiedText).results()
                .map(java.util.regex.MatchResult::group)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!imageRows.isBlank()) {
            visibleText = visibleText.isBlank() ? imageRows : visibleText + "\n" + imageRows;
        }
        return new AiVisibleImageContext(visibleText, parseImageRefs(verified.imageRefs()));
    }

    private TeacherDocumentBlockResponse sourceBlockForEvidence(TeachingEvidence evidence, RequestSubject subject) {
        if (evidence == null || evidence.sourceDocumentId().isBlank() || evidence.chunkId().isBlank()) return null;
        if (isInspectableTeacherDocument(evidence) && resourceSearchService != null) {
            return resourceSearchService.listVisibleBlocks(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                    evidence.sourceDocumentId()).stream()
                    .filter(block -> evidence.chunkId().equals(block.blockId())
                            || evidence.chunkId().equals(block.externalBlockId()))
                    .findFirst().orElse(null);
        }
        if (evidence.imageRefs().isEmpty()) return null;
        try {
            return new TeacherDocumentBlockResponse(evidence.chunkId(), evidence.sourceDocumentId(), evidence.chunkId(),
                    "retrieval", 0, "", "", evidence.pageNo(), null, "", "", evidence.snippet(), evidence.snippet(),
                    objectMapper.writeValueAsString(evidence.imageRefs()), "[]", "[]", "[]", "", 1D, "active");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize authorized image bindings", exception);
        }
    }

    private static String rewriteExcerptRows(String excerpt, String rewrittenSource) {
        Map<String, String> rewrittenRowsByTarget = new java.util.HashMap<>();
        Matcher sourceImages = markdownImageMatcher(rewrittenSource);
        while (sourceImages.find()) {
            String row = sourceImages.group();
            rewrittenRowsByTarget.put(markdownImageTarget(row), row);
        }
        Matcher excerptImages = markdownImageMatcher(excerpt);
        StringBuffer visible = new StringBuffer(excerpt.length());
        while (excerptImages.find()) {
            String replacement = rewrittenRowsByTarget.get(markdownImageTarget(excerptImages.group()));
            excerptImages.appendReplacement(visible, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        excerptImages.appendTail(visible);
        return visible.toString().strip();
    }

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^\\r\\n)]*\\)");

    private static Matcher markdownImageMatcher(String text) {
        return MARKDOWN_IMAGE.matcher(text == null ? "" : text);
    }

    private static String markdownImageTarget(String markdownImage) {
        int marker = markdownImage.indexOf("](");
        return marker < 0 ? "" : markdownImage.substring(marker).strip();
    }

    private static String stripUnboundMarkdownImages(String text) {
        return markdownImageMatcher(text).replaceAll("").strip();
    }

    private record AiVisibleImageContext(String text, List<Map<String, Object>> imageRefs) {
    }

    @PostMapping("/handout-document-read")
    public Map<String, Object> handoutDocumentRead(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutDocumentReadRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        TeachingEvidence evidence = authorizedHandoutDocumentEvidence(request.runId(), request.documentRef());
        int maxBlocks = boundedLimit(request.maxBlocks(), MAX_HANDOUT_DOCUMENT_BLOCKS);
        int maxChars = boundedLimit(request.maxChars(), MAX_HANDOUT_DOCUMENT_CHARS);
        List<TeacherDocumentBlockResponse> visibleBlocks = prioritizeAuthorizedEvidenceBlock(
                evidence, readAuthorizedBlocks(evidence, subject));
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), visibleBlocks, maxBlocks, maxChars, subject);
        auditHandoutInspection(request.runId(), "read", request.documentRef(), blocks.size());
        return Map.of("runId", request.runId(), "documentRef", request.documentRef(), "blocks", blocks);
    }

    @PostMapping("/handout-canonical-question-read")
    public Map<String, Object> handoutCanonicalQuestionRead(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutCanonicalQuestionReadRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        TeachingEvidence evidence = authorizedHandoutDocumentEvidence(request.runId(), request.documentRef());
        if (!isCanonicalMathPaper(evidence) || canonicalPaperBlockReader == null
                || evidence.canonicalQuestionNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized canonical question is unavailable");
        }
        int maxBlocks = boundedLimit(request.maxBlocks(), 1);
        int maxChars = boundedLimit(request.maxChars(), MAX_HANDOUT_DOCUMENT_CHARS);
        List<TeacherDocumentBlockResponse> visibleBlocks;
        try {
            visibleBlocks = canonicalPaperBlockReader.readQuestion(
                    evidence.sourceDocumentId(), evidence.canonicalQuestionNumber());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized canonical question is unavailable");
        }
        List<TeacherDocumentBlockResponse> rewrittenBlocks = imageRewriter.rewrite(
                request.runId(), visibleBlocks, subject, (documentId, logicalPath, viewer) -> canonicalAssetService != null
                        && canonicalAssetService.openVisibleQuestionFigure(
                        documentId, evidence.canonicalQuestionNumber(), logicalPath, viewer).isPresent());
        List<Map<String, Object>> blocks = compactAlreadyRewrittenBlocks(request.runId(), rewrittenBlocks, maxBlocks, maxChars);
        auditHandoutInspection(request.runId(), "canonical-question-read", request.documentRef(), blocks.size());
        return Map.of("runId", request.runId(), "documentRef", request.documentRef(), "blocks", blocks);
    }
    private boolean isContextEvidenceVisible(TeachingEvidence evidence, RequestSubject subject) {
        if (!hasDocumentReferenceCandidate(evidence)) {
            return true;
        }
        if (isPublicTextbook(evidence)) {
            return textbookBlockReader == null || textbookBlockReader.isAvailable(evidence.sourceDocumentId());
        }
        if (isCanonicalMathPaper(evidence)) {
            return canonicalPaperBlockReader != null && canonicalPaperBlockReader.isAvailable(evidence.sourceDocumentId());
        }
        return resourceSearchService == null
                || resourceSearchService.isSourceAvailable(subject.tenantId(), evidence.sourceDocumentId());
    }

    @PostMapping("/handout-document-page-read")
    public Map<String, Object> handoutDocumentPageRead(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutDocumentPageReadRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        TeachingEvidence evidence = authorizedHandoutDocumentEvidence(request.runId(), request.documentRef());
        if (!isPublicTextbook(evidence) || textbookBlockReader == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized textbook source is unavailable");
        }
        int maxBlocks = boundedLimit(request.maxBlocks(), MAX_HANDOUT_DOCUMENT_BLOCKS);
        int maxChars = boundedLimit(request.maxChars(), MAX_HANDOUT_DOCUMENT_CHARS);
        List<TeacherDocumentBlockResponse> visibleBlocks = textbookBlockReader.readPageWindow(
                evidence.sourceDocumentId(), evidence.pageNo(), request.pageNo(), request.pageRadius());
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), visibleBlocks, maxBlocks, maxChars, subject);
        auditHandoutInspection(request.runId(), "page-read", request.documentRef(), blocks.size());
        return Map.of("runId", request.runId(), "documentRef", request.documentRef(), "blocks", blocks);
    }

    /** Determines whether an evidence row has a reader in its own source domain. */
    private boolean isInspectableEvidence(TeachingEvidence evidence, RequestSubject subject) {
        if (!hasDocumentReferenceCandidate(evidence)) {
            return false;
        }
        if (isPublicTextbook(evidence)) {
            return textbookBlockReader != null && textbookBlockReader.isAvailable(evidence.sourceDocumentId());
        }
        if (isCanonicalMathPaper(evidence)) {
            return canonicalPaperBlockReader != null && canonicalPaperBlockReader.isAvailable(evidence.sourceDocumentId());
        }
        return resourceSearchService != null
                && resourceSearchService.isSourceAvailable(subject.tenantId(), evidence.sourceDocumentId());
    }

    private static boolean hasDocumentReferenceCandidate(TeachingEvidence evidence) {
        return evidence != null && evidence.sourceDocumentId() != null && !evidence.sourceDocumentId().isBlank()
                && (isPublicTextbook(evidence) || isCanonicalMathPaper(evidence) || isInspectableTeacherDocument(evidence));
    }

    private static boolean isPublicTextbook(TeachingEvidence evidence) {
        return evidence != null && "PUBLIC_TEXTBOOK".equals(evidence.sourceScope());
    }

    /** Identifies a manifest-published high-school paper whose opaque document reference is resolved in Java only. */
    private static boolean isCanonicalMathPaper(TeachingEvidence evidence) {
        return evidence != null && "CANONICAL_MATH_PAPER".equals(evidence.sourceScope());
    }

    /** Identifies evidence whose document ID belongs to the tenant-scoped teacher reader contract. */
    private static boolean isInspectableTeacherDocument(TeachingEvidence evidence) {
        if (evidence == null || !("TEACHER_RESOURCE".equals(evidence.sourceScope())
                || "QUESTION_BANK".equals(evidence.sourceScope()))
                || evidence.sourceDocumentId() == null || evidence.sourceDocumentId().isBlank()) {
            return false;
        }
        String sourceType = evidence.sourceType() == null ? "" : evidence.sourceType().strip().toLowerCase(java.util.Locale.ROOT);
        return sourceType.isEmpty() || "feishu".equals(sourceType) || "teacher_resource".equals(sourceType)
                || "question_bank".equals(sourceType);
    }

    /** Dispatches an already-authorized source to its own internal reader without exposing its storage location. */
    private List<TeacherDocumentBlockResponse> readAuthorizedBlocks(TeachingEvidence evidence, RequestSubject subject) {
        try {
            if (isPublicTextbook(evidence) && textbookBlockReader != null) {
                return textbookBlockReader.read(evidence.sourceDocumentId());
            }
            if (isCanonicalMathPaper(evidence) && canonicalPaperBlockReader != null) {
                return canonicalPaperBlockReader.read(evidence.sourceDocumentId());
            }
            if (isInspectableTeacherDocument(evidence) && resourceSearchService != null) {
                return resourceSearchService.listVisibleBlocks(
                        subject.tenantId(), subject.subjectType(), subject.subjectId(), evidence.sourceDocumentId());
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // Source-domain reader failures remain opaque; callers never receive catalog paths or source identifiers.
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized handout source is unavailable");
    }

    /**
     * Anchors a bounded deep read on the exact persisted retrieval hit. The source reader still supplies original
     * parsed blocks only; this ordering prevents an entire textbook's unrelated opening chapter from displacing the
     * authorized hit before the worker reaches its hard block and character limits.
     */
    private static List<TeacherDocumentBlockResponse> prioritizeAuthorizedEvidenceBlock(
            TeachingEvidence evidence, List<TeacherDocumentBlockResponse> blocks) {
        if (blocks == null || blocks.isEmpty() || evidence.chunkId() == null || evidence.chunkId().isBlank()) {
            return blocks == null ? List.of() : blocks;
        }
        String authorizedBlockId = evidence.chunkId();
        int authorizedPageNo = evidence.pageNo();
        return blocks.stream()
                .sorted(Comparator.<TeacherDocumentBlockResponse>comparingInt(block -> {
                    if (authorizedBlockId.equals(block.blockId()) || authorizedBlockId.equals(block.externalBlockId())) {
                        return 0;
                    }
                    if (authorizedPageNo > 0 && block.pageNo() != null
                            && Math.abs(block.pageNo() - authorizedPageNo) <= 1) {
                        return 1;
                    }
                    return 2;
                }).thenComparingInt(block -> block.pageNo() == null ? Integer.MAX_VALUE
                        : Math.abs(block.pageNo() - authorizedPageNo))
                        .thenComparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
    }

    /** Searches only parsed blocks belonging to one previously matched document; keyword and result size are bounded. */
    @PostMapping("/handout-document-search")
    public Map<String, Object> handoutDocumentSearch(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutDocumentSearchRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        TeachingEvidence evidence = authorizedHandoutDocumentEvidence(request.runId(), request.documentRef());
        String keyword = request.keyword().strip();
        if (keyword.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Handout document keyword exceeds limit");
        }
        int maxBlocks = boundedLimit(request.maxBlocks(), MAX_HANDOUT_SEARCH_BLOCKS);
        int maxChars = boundedLimit(request.maxChars(), MAX_HANDOUT_SEARCH_CHARS);
        List<TeacherDocumentBlockResponse> matches = readAuthorizedBlocks(evidence, subject).stream()
                .filter(block -> containsKeyword(block.rawText(), keyword) || containsKeyword(block.normalizedText(), keyword))
                .sorted(Comparator.comparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), matches, maxBlocks, maxChars, subject);
        auditHandoutInspection(request.runId(), "keyword-search", request.documentRef(), blocks.size());
        return Map.of("runId", request.runId(), "documentRef", request.documentRef(), "blocks", blocks);
    }

    /**
     * Lets the Python plan writer opt into private teacher retrieval. Identity is derived from the durable task;
     * the model supplies only a bounded query and never receives a filesystem path or source URL.
     */
    @PostMapping("/handout-teacher-resource-search")
    public Map<String, Object> handoutTeacherResourceSearch(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutTeacherResourceSearchRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        String query = request.query().strip();
        if (query.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Handout teacher-resource query exceeds limit");
        }
        TeacherResourceBlockSearchResponse response = resourceSearchService.search(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), query, request.limit(),
                "/internal/agent-tools/v1/handout-teacher-resource-search");
        // A model-selected private hit becomes part of this run's durable authorization snapshot before its opaque
        // document reference leaves Java. This is not a second retrieval path: the broker has just performed the
        // bounded AI-generated query under the task's persisted subject.
        persistTeacherSearchEvidence(request.runId(), subject, response.hits());
        List<Map<String, Object>> items = response.hits().stream().map(hit -> {
            TeachingEvidence evidence = teacherEvidence(hit, subject);
            AiVisibleImageContext imageContext = rewriteAiVisibleExcerpt(
                    request.runId(), evidence, compactEvidence(hit.evidenceText(), hit.snippet()), subject);
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("ref", evidenceRef(request.runId(), evidence));
            item.put("title", hit.documentTitle() == null ? "" : hit.documentTitle());
            item.put("documentName", hit.documentTitle() == null ? "" : hit.documentTitle());
            item.put("documentRef", documentRef(request.runId(), preferredFileDocumentId(hit)));
            item.put("transparentRef", transparentFeishuSourceRef(hit));
            item.put("fileName", hit.fileName() == null ? "" : hit.fileName());
            item.put("excerpt", imageContext.text());
            item.put("imageRefs", imageContext.imageRefs());
            return Map.copyOf(item);
        }).toList();
        auditHandoutInspection(request.runId(), "model-teacher-search", fingerprint(query), items.size());
        return Map.of("runId", request.runId(), "items", items);
    }

    /**
     * Resolves an opaque graph run from the one business store that owns it.
     *
     * <p>During the compatibility canary, older multi-agent rows remain readable. Newly created handouts are
     * teaching tasks, so the Python graph must derive the exact same tenant/subject from that task instead of
     * requiring a parallel workflow row or accepting identity fields from Python.</p>
     */
    private RequestSubject subjectForHandoutRun(String runId) {
        if (teachingTaskStore != null) {
            TeachingTaskResponse task = teachingTaskStore.findByTaskId(runId).orElse(null);
            if (task != null) {
                return new RequestSubject(
                        task.tenantId(), task.subjectType(), task.subjectId(), "agent-worker").normalize();
            }
        }
        if (workflowStore != null) {
            MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId).orElse(null);
            if (workflow != null) {
                return new RequestSubject(
                        workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
            }
        }
        if (teachingTaskStore == null && workflowStore == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Handout context stores are unavailable");
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
    }

    private List<TeachingEvidence> durableHandoutEvidence(String runId) {
        // Canonical Python workflows own their evidence ledger; never substitute an unrelated legacy task snapshot.
        if (workflowStore != null) {
            MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId).orElse(null);
            if (workflow != null) {
                List<TeachingEvidence> snapshot = workflow.stages().stream()
                        .filter(stage -> "resource_curation".equals(stage.stageCode()))
                        .map(MultiAgentWritingResponse.StageResult::generatedContent)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .map(this::readEvidenceSnapshot)
                        .orElse(List.of());
                if (!snapshot.isEmpty()) return snapshot;
            }
        }
        if (teachingTaskStore != null) {
            TeachingTaskResponse task = teachingTaskStore.findByTaskId(runId).orElse(null);
            if (task != null) return task.evidence() == null ? List.of() : task.evidence();
        }
        return List.of();
    }

    private static String sourceScopeFromTransparentRef(String transparentRef, String fallback) {
        if (transparentRef.startsWith("textbook://")) return "PUBLIC_TEXTBOOK";
        if (transparentRef.startsWith("feishu://")) return "TEACHER_RESOURCE";
        if (transparentRef.startsWith("gaokao://")) return "CANONICAL_MATH_PAPER";
        return fallback;
    }

    private static String sourceTypeFromScope(String sourceScope) {
        return switch (sourceScope) {
            case "PUBLIC_TEXTBOOK" -> "public_textbook";
            case "CANONICAL_MATH_PAPER" -> "gaokao";
            case "TEACHER_RESOURCE" -> "feishu";
            default -> sourceScope;
        };
    }

    private static String chunkIdFromTransparentRef(String transparentRef) {
        if (transparentRef == null || transparentRef.isBlank()) return "";
        int marker = transparentRef.lastIndexOf("/chunk/");
        if (marker >= 0) return transparentRef.substring(marker + "/chunk/".length());
        marker = transparentRef.lastIndexOf("/block/");
        if (marker >= 0) return transparentRef.substring(marker + "/block/".length());
        return "";
    }

    private static String canonicalQuestionNumberFromTransparentRef(String transparentRef) {
        if (transparentRef == null || transparentRef.isBlank()) return "";
        int marker = transparentRef.lastIndexOf("/question/");
        return marker >= 0 ? transparentRef.substring(marker + "/question/".length()) : "";
    }

    private static String sourceDocumentIdFromTransparentRef(String transparentRef) {
        if (transparentRef == null || transparentRef.isBlank()) return "";
        if (transparentRef.startsWith("textbook://")) {
            String value = transparentRef.substring("textbook://".length());
            int marker = value.indexOf("/chunk/");
            return marker >= 0 ? value.substring(0, marker) : "";
        }
        if (transparentRef.startsWith("feishu://")) {
            int marker = transparentRef.indexOf("/resource/");
            if (marker < 0) return "";
            String value = transparentRef.substring(marker + "/resource/".length());
            int blockMarker = value.indexOf("/block/");
            return blockMarker >= 0 ? value.substring(0, blockMarker) : "";
        }
        if (transparentRef.startsWith("gaokao://canonical/")) {
            String value = transparentRef.substring("gaokao://canonical/".length());
            int marker = value.indexOf("/question/");
            return marker >= 0 ? value.substring(0, marker) : "";
        }
        return "";
    }

    private List<TeachingEvidence> readEvidenceSnapshot(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root != null && root.isObject()) {
                java.util.ArrayList<TeachingEvidence> items = new java.util.ArrayList<>();
                appendEvidenceSnapshotRows(items, root.path("items"));
                appendEvidenceSnapshotRows(items, root.path("inspectedItems"));
                if (!items.isEmpty()) return List.copyOf(items);
            }
            if (root != null && root.isArray()) {
                return objectMapper.convertValue(root, new TypeReference<List<TeachingEvidence>>() { });
            }
        } catch (Exception ignored) {
            // Older workflow rows may contain a legacy snapshot; an invalid snapshot remains fail-closed.
        }
        return List.of();
    }

    private void appendEvidenceSnapshotRows(List<TeachingEvidence> items, JsonNode rows) {
        if (rows == null || !rows.isArray()) return;
        for (JsonNode item : rows) {
            if (item == null || !item.isObject()) continue;
            String transparentRef = item.path("transparentRef").asText("");
            String sourceScope = sourceScopeFromTransparentRef(transparentRef, item.path("sourceScope").asText(""));
            String sourceDocumentId = item.path("sourceDocumentId").asText("");
            if (sourceDocumentId.isBlank()) {
                sourceDocumentId = sourceDocumentIdFromTransparentRef(transparentRef);
            }
            String chunkId = item.path("chunkId").asText("");
            if (chunkId.isBlank()) {
                chunkId = chunkIdFromTransparentRef(transparentRef);
            }
            String title = item.path("sourceTitle").asText(item.path("title").asText(""));
            String snippet = item.path("snippet").asText(item.path("excerpt").asText(""));
            items.add(new TeachingEvidence(
                    sourceScope,
                    title,
                    chunkId,
                    item.path("pageNo").asInt(0),
                    snippet,
                    "",
                    "",
                    sourceDocumentId,
                    sourceTypeFromScope(sourceScope),
                    "",
                    item.path("sourceRelativePath").asText(item.path("sourcePath").asText("")),
                    readStringList(item.path("assetIds")),
                    item.path("canonicalQuestionNumber").asText(canonicalQuestionNumberFromTransparentRef(transparentRef)),
                    readImageRefs(item.path("imageRefs"))));
        }
    }

    private static List<Map<String, String>> readImageRefs(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, String>> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (values.size() >= 12 || item == null || !item.isObject()) continue;
            String markdownLine = safeImageField(item, "markdownLine", 12000);
            String logicalPath = safeImageField(item, "logicalPath", 1200);
            if (markdownLine.isBlank() || logicalPath.isBlank()) continue;
            values.add(Map.of("markdownLine", markdownLine, "logicalPath", logicalPath));
        }
        return List.copyOf(values);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").strip();
            if (!value.isBlank() && value.length() <= 120 && !values.contains(value)) values.add(value);
            if (values.size() >= 8) break;
        }
        return List.copyOf(values);
    }

    private void persistWorkflowEvidence(String runId, List<TeachingEvidence> evidence) {
        if (workflowStore == null || evidence == null) return;
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId).orElse(null);
        if (workflow == null) return;
        try {
            List<Map<String, Object>> rows = evidence.stream()
                    .map(item -> Map.<String, Object>ofEntries(
                            Map.entry("ref", evidenceRef(runId, item)),
                            Map.entry("transparentRef", transparentEvidenceReference(item)),
                            Map.entry("sourceScope", item.sourceScope()),
                            Map.entry("sourceTitle", item.sourceTitle()),
                            Map.entry("sourceDocumentId", item.sourceDocumentId()),
                            Map.entry("chunkId", item.chunkId()),
                            Map.entry("pageNo", item.pageNo()),
                            Map.entry("snippet", item.snippet()),
                            Map.entry("assetIds", item.assetIds()),
                            Map.entry("imageRefs", item.imageRefs()),
                            Map.entry("canonicalQuestionNumber", item.canonicalQuestionNumber())))
                    .toList();
            String content = objectMapper.writeValueAsString(Map.of("items", rows));
            List<MultiAgentWritingResponse.StageResult> stages = new java.util.ArrayList<>(workflow.stages());
            stages.removeIf(stage -> "resource_curation".equals(stage.stageCode()));
            stages.add(new MultiAgentWritingResponse.StageResult("resource_curation", "TeacherAssistantAgent", runId + ":resource_curation",
                    "java-broker", "", "COMPLETED", new AgentRunExecuteResponse.TokenUsage(0, 0, 0),
                    "Authorized evidence snapshot persisted.", content, 0L));
            workflowStore.save(new MultiAgentWritingWorkflowRecord(workflow.workflowId(), workflow.tenantId(), workflow.subjectType(),
                    workflow.subjectId(), workflow.status(), workflow.createdAt(), java.time.Instant.now(), stages,
                    workflow.totalUsage(), workflow.message()));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist authorized handout evidence", exception);
        }
    }

    private static String transparentEvidenceReference(TeachingEvidence evidence) {
        if (evidence == null) return "";
        if ("PUBLIC_TEXTBOOK".equals(evidence.sourceScope())) {
            return "textbook://" + evidence.sourceDocumentId() + "/chunk/" + evidence.chunkId();
        }
        if ("TEACHER_RESOURCE".equals(evidence.sourceScope())) {
            return "feishu://group/TEACHER_SHARED/resource/" + evidence.sourceDocumentId()
                    + "/block/" + evidence.chunkId();
        }
        if ("CANONICAL_MATH_PAPER".equals(evidence.sourceScope())) {
            return "gaokao://canonical/" + evidence.sourceDocumentId()
                    + "/question/" + evidence.canonicalQuestionNumber();
        }
        return "";
    }

    private static String compactEvidence(String evidenceText, String snippet) {
        String value = evidenceText == null || evidenceText.isBlank() ? snippet : evidenceText;
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        return normalized.length() <= 3000 ? normalized : normalized.substring(0, 2997) + "...";
    }

    /** Emits metadata-only audit records; source text, paths, URLs, query text and credentials never enter logs. */
    private void auditHandoutInspection(String runId, String operation, String opaqueReference, int resultCount) {
        LOGGER.info("handout_document_inspection runId={} operation={} referenceFingerprint={} resultCount={}",
                runId, operation, fingerprint(opaqueReference), resultCount);
    }

    private TeachingEvidence authorizedHandoutDocumentEvidence(String runId, String opaqueReference) {
        return durableHandoutEvidence(runId).stream()
                .filter(evidence -> hasDocumentReferenceCandidate(evidence))
                .filter(evidence -> documentRef(runId, evidence.sourceDocumentId()).equals(opaqueReference))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Handout document reference is not authorized for this run"));
    }

    /**
     * Appends only the current subject's broker-verified teacher hits to the task evidence ledger.
     *
     * <p>Deduplication uses the same document/block identity that generates the run-scoped evidence reference, so
     * repeated plan queries remain idempotent while distinct real hits are retained for the final source projection.
     * No query text, path, URL, or database detail is persisted in this bridge.</p>
     */
    /**
     * Creates the exact persisted ledger representation so returned opaque refs remain reloadable by handout-context.
     *
     * <p>The teacher-resource search index may legitimately return a public textbook hit. Its source scope must retain
     * that authoritative reader domain: labelling it as a teacher resource makes the later run-scoped opaque document
     * reference unresolvable and incorrectly produces a 403. This changes no authorization boundary; the same task,
     * opaque reference and source-specific reader are still required.</p>
     */
    private TeachingEvidence teacherEvidence(TeacherResourceBlockSearchResponse.Hit hit, RequestSubject subject) {
        String sourceType = hit.sourceType() == null ? "teacher_resource" : hit.sourceType();
        boolean publicTextbook = "public_textbook".equalsIgnoreCase(sourceType.trim())
                || "PUBLIC_TEXTBOOK".equalsIgnoreCase(
                        hit.permissionScope() == null ? "" : hit.permissionScope().trim());
        String sourceScope = publicTextbook ? "PUBLIC_TEXTBOOK" : "TEACHER_RESOURCE";
        String sourceDocumentId = preferredFileDocumentId(hit);
        List<Map<String, String>> imageRefs = authoritativeImageRefs(sourceScope, sourceDocumentId, hit.blockId(), subject);
        return new TeachingEvidence(
                sourceScope,
                hit.documentTitle() == null ? "" : hit.documentTitle(),
                hit.blockId(),
                hit.pageNo() == null ? 0 : hit.pageNo(),
                compactEvidence(hit.evidenceText(), hit.snippet()),
                "", "", sourceDocumentId, sourceType,
                "", "", hit.imageAssetIds() == null ? List.of() : hit.imageAssetIds(), "", imageRefs);
    }

    /**
     * Reads image bindings only from the authoritative persisted FILE block. Vector metadata remains text-only and
     * cannot nominate a figure; a missing exact block binding returns no image row for the model.
     */
    private List<Map<String, String>> authoritativeImageRefs(
            String sourceScope, String documentId, String blockId, RequestSubject subject) {
        if (!"TEACHER_RESOURCE".equals(sourceScope) || resourceSearchService == null
                || documentId == null || documentId.isBlank() || blockId == null || blockId.isBlank()) {
            return List.of();
        }
        if (subject == null) {
            return List.of();
        }
        return resourceSearchService.listVisibleBlocks(subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId)
                .stream()
                .filter(block -> blockId.equals(block.blockId()) || blockId.equals(block.externalBlockId()))
                .findFirst()
                .map(block -> readImageRefs(uncheckedJson(block.imageRefs())))
                .orElse(List.of());
    }

    private JsonNode uncheckedJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "[]" : value);
        } catch (IOException exception) {
            return objectMapper.createArrayNode();
        }
    }

    /**
     * A retrieval hit may name a ROOT source for ranking while its block belongs to one physical FILE.
     * Block authorization and image bindings are stored against that FILE, so prefer its already-returned identity.
     */
    private static String preferredFileDocumentId(TeacherResourceBlockSearchResponse.Hit hit) {
        if (hit.fileDocumentId() != null && !hit.fileDocumentId().isBlank()) {
            return hit.fileDocumentId().strip();
        }
        return hit.documentId() == null ? "" : hit.documentId().strip();
    }

    private String transparentFeishuSourceRef(TeacherResourceBlockSearchResponse.Hit hit) {
        String documentId = preferredFileDocumentId(hit);
        String blockId = hit.blockId() == null ? "" : hit.blockId().strip();
        if (documentId.isBlank() || blockId.isBlank()) {
            return "";
        }
        return "feishu://group/TEACHER_SHARED/resource/" + documentId + "/block/" + blockId;
    }

    private void persistTeacherSearchEvidence(
            String runId, RequestSubject subject, List<TeacherResourceBlockSearchResponse.Hit> hits) {
        if (hits == null || hits.isEmpty()) return;

        // Python-owned MCP workflows keep the authoritative evidence ledger in the workflow store. The compatibility
        // teaching-task store may contain a row with the same id during canary operation, but writing there first would
        // make the subsequent broker reads and writer checkpoint observe a stale workflow snapshot.
        if (workflowStore != null
                && workflowStore.findByIdInternal(runId).isPresent()) {
            List<TeachingEvidence> merged = mergeTeacherSearchEvidence(
                    durableHandoutEvidence(runId), hits, subject);
            persistWorkflowEvidence(runId, merged);
            return;
        }

        if (teachingTaskStore != null) {
            TeachingTaskResponse task = teachingTaskStore.findByTaskId(runId).orElse(null);
            if (task != null) {
                List<TeachingEvidence> merged = mergeTeacherSearchEvidence(
                        task.evidence() == null ? List.of() : task.evidence(), hits, subject);
                if (!merged.equals(task.evidence() == null ? List.of() : task.evidence())) {
                    String ownerKey = subject.tenantId() + ":" + subject.subjectType() + ":" + subject.subjectId();
                    teachingTaskStore.save(ownerKey, ownerKey + ":" + task.clientRequestId(), task.withEvidence(merged));
                }
                return;
            }
        }

        List<TeachingEvidence> merged = mergeTeacherSearchEvidence(durableHandoutEvidence(runId), hits, subject);
        persistWorkflowEvidence(runId, merged);
    }

    /**
     * Merges newly returned opaque image identities into the durable evidence row for the same block.
     *
     * <p>The first search may have authorized text before image projection is refreshed. Replacing the row by block
     * identity would silently discard the later asset ids and make an otherwise authorized image unavailable to the
     * writer/export chain. Text and source ownership remain durable; only the asset-id set is unioned.</p>
     */
    private List<TeachingEvidence> mergeTeacherSearchEvidence(
            List<TeachingEvidence> existing,
            List<TeacherResourceBlockSearchResponse.Hit> hits,
            RequestSubject subject) {
        List<TeachingEvidence> merged = new java.util.ArrayList<>(existing == null ? List.of() : existing);
        for (TeacherResourceBlockSearchResponse.Hit hit : hits) {
            if (hit == null || hit.documentId() == null || hit.documentId().isBlank()
                    || hit.blockId() == null || hit.blockId().isBlank()) continue;
            TeachingEvidence candidate = teacherEvidence(hit, subject);
            int index = findEvidenceIndex(merged, candidate);
            if (index < 0) {
                merged.add(candidate);
                continue;
            }
            TeachingEvidence prior = merged.get(index);
            TeachingEvidence combined = mergeEvidenceAssets(prior, candidate);
            if (!combined.equals(prior)) merged.set(index, combined);
        }
        return List.copyOf(merged);
    }

    private static int findEvidenceIndex(List<TeachingEvidence> evidence, TeachingEvidence candidate) {
        for (int index = 0; index < evidence.size(); index++) {
            TeachingEvidence item = evidence.get(index);
            if (item.sourceScope().equals(candidate.sourceScope())
                    && item.sourceDocumentId().equals(candidate.sourceDocumentId())
                    && item.chunkId().equals(candidate.chunkId())) {
                return index;
            }
        }
        return -1;
    }

    private static TeachingEvidence mergeEvidenceAssets(
            TeachingEvidence first, TeachingEvidence second) {
        List<String> assets = new java.util.ArrayList<>(first.assetIds());
        for (String assetId : second.assetIds()) {
            if (assetId != null && !assetId.isBlank() && !assets.contains(assetId)) {
                assets.add(assetId);
            }
        }
        List<Map<String, String>> imageRefs = new java.util.ArrayList<>(first.imageRefs());
        for (Map<String, String> imageRef : second.imageRefs()) {
            if (!imageRefs.contains(imageRef)) imageRefs.add(imageRef);
        }
        if (assets.equals(first.assetIds()) && imageRefs.equals(first.imageRefs())) return first;
        return new TeachingEvidence(first.sourceScope(), first.sourceTitle(), first.chunkId(), first.pageNo(),
                first.snippet(), first.imagePath(), first.imageDescription(), first.sourceDocumentId(), first.sourceType(),
                first.sourceUrl(), first.sourcePath(), assets, first.canonicalQuestionNumber(), imageRefs);
    }

    private List<TeachingEvidence> authorizedHandoutEvidence(
            String runId, List<String> opaqueReferences) {
        List<TeachingEvidence> durable = durableHandoutEvidence(runId);
        if (durable.isEmpty() && teachingTaskStore == null && workflowStore == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
        }
        java.util.ArrayList<com.doob.mathagent.teaching.TeachingEvidence> resolved = new java.util.ArrayList<>();
        for (String reference : opaqueReferences) {
            if (reference == null || !reference.matches("ev_[0-9a-f]{32}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Handout evidence reference is malformed");
            }
            com.doob.mathagent.teaching.TeachingEvidence evidence = durable.stream()
                    .filter(candidate -> evidenceRef(runId, candidate).equals(reference))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Handout evidence reference is not authorized for this run"));
            if (!resolved.contains(evidence)) {
                resolved.add(evidence);
            }
        }
        return List.copyOf(resolved);
    }

    private List<Map<String, Object>> parseImageRefs(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root == null || !root.isArray()) return List.of();
            java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
            for (JsonNode value : root) {
                if (result.size() >= 50 || value == null || !value.isObject()) break;
                String markdownLine = safeImageField(value, "markdownLine", 12000);
                String logicalPath = safeImageField(value, "logicalPath", 1200);
                if (markdownLine.isBlank() || logicalPath.isBlank()) continue;
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("markdownLine", markdownLine);
                item.put("logicalPath", logicalPath);
                result.add(Map.copyOf(item));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static String safeImageField(JsonNode source, String field, int maxLength) {
        JsonNode value = source.path(field);
        if (!value.isTextual()) return "";
        String text = value.asText("");
        return text.isBlank() || text.length() > maxLength || text.contains("http://") || text.contains("https://")
                || text.contains("data:") || text.contains("\\\\") || text.contains("..") ? "" : text;
    }

    /**
     * Keeps a bounded source read usable when a sibling block carries a stale image binding. The block is excluded
     * rather than exposing an unmaterializable row or failing unrelated authorized text in the same document.
     */
    private boolean hasAuthorizedImageBindings(TeacherDocumentBlockResponse block, RequestSubject subject) {
        if (block.imageRefs() == null || block.imageRefs().isBlank() || assetService == null) {
            return true;
        }
        for (Map<String, Object> imageRef : parseImageRefs(block.imageRefs())) {
            String logicalPath = String.valueOf(imageRef.getOrDefault("logicalPath", ""));
            if (!assetService.openVisibleLogicalAsset(block.documentId(), logicalPath, subject).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> compactBlocks(
            String runId, List<TeacherDocumentBlockResponse> source, int maxBlocks, int maxChars, RequestSubject subject) {
        // Do not authorize blocks that cannot reach the worker because of this endpoint's bounded response contract.
        // A stale image binding outside the visible page must not prevent a valid earlier source row from being read.
        List<TeacherDocumentBlockResponse> visibleSource = source == null ? List.of() : source.stream()
                .filter(java.util.Objects::nonNull)
                .limit(maxBlocks)
                .filter(block -> hasAuthorizedImageBindings(block, subject))
                .toList();
        List<TeacherDocumentBlockResponse> rewrittenSource = imageRewriter.rewrite(
                runId,
                visibleSource,
                subject,
                assetService == null ? null : (documentId, logicalPath, viewer) -> assetService
                        .openVisibleLogicalAsset(documentId, logicalPath, viewer).isPresent());
        int remaining = maxChars;
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (TeacherDocumentBlockResponse block : rewrittenSource) {
            if (result.size() >= maxBlocks || remaining <= 0) break;
            String text = compactEvidence(block.rawText(), block.normalizedText());
            // Preserve the qualified Markdown image binding produced during Feishu publication; the worker must read
            // the same persisted identity rather than reconstructing a basename from an opaque asset id.
            if (text.isBlank() || text.length() > remaining) continue;
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("ref", blockEvidenceRef(runId, block.documentId(), block.blockId()));
            item.put("text", text);
            item.put("section", block.section() == null ? "" : block.section());
            item.put("pageNo", block.pageNo() == null ? 0 : block.pageNo());
            item.put("articlePath", block.sourcePath() == null ? "" : block.sourcePath());
            item.put("imageRefs", parseImageRefs(block.imageRefs()));
            result.add(Map.copyOf(item));
            remaining -= text.length();
        }
        return List.copyOf(result);
    }

    /**
     * Compacts blocks whose image rows have already passed the source-domain authorization and alias rewrite.
     * Canonical question reads use this after their figures/ verifier so they never fall through the Feishu gateway.
     */
    private List<Map<String, Object>> compactAlreadyRewrittenBlocks(
            String runId, List<TeacherDocumentBlockResponse> source, int maxBlocks, int maxChars) {
        int remaining = maxChars;
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (TeacherDocumentBlockResponse block : source == null ? List.<TeacherDocumentBlockResponse>of() : source) {
            if (block == null || result.size() >= maxBlocks || remaining <= 0) break;
            String text = compactEvidence(block.rawText(), block.normalizedText());
            if (text.isBlank() || text.length() > remaining) continue;
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("ref", blockEvidenceRef(runId, block.documentId(), block.blockId()));
            item.put("text", text);
            item.put("section", block.section() == null ? "" : block.section());
            item.put("pageNo", block.pageNo() == null ? 0 : block.pageNo());
            item.put("articlePath", block.sourcePath() == null ? "" : block.sourcePath());
            item.put("imageRefs", parseImageRefs(block.imageRefs()));
            result.add(Map.copyOf(item));
            remaining -= text.length();
        }
        return List.copyOf(result);
    }
    private static boolean containsKeyword(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }

    private static int boundedLimit(Integer requested, int ceiling) {
        if (requested == null || requested < 1) return ceiling;
        return Math.min(requested, ceiling);
    }

    /** Opaque, run-scoped references prevent a worker from naming raw document or block IDs in later calls. */
    private String documentRef(String runId, String documentId) {
        return "doc_" + fingerprint(runId + "|document|" + documentId);
    }

    private String evidenceRef(String runId, com.doob.mathagent.teaching.TeachingEvidence evidence) {
        String assets = evidence.assetIds() == null
                ? ""
                : evidence.assetIds().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return "ev_" + fingerprint(runId + "|evidence|" + evidence.sourceDocumentId() + "|"
                + evidence.sourceScope() + "|" + evidence.sourceTitle() + "|" + evidence.chunkId()
                + "|assets=" + assets);
    }

    /** 文档阅读块与任务初始证据使用不同命名空间，防止引用被跨接口重放。 */
    private String blockEvidenceRef(String runId, String documentId, String blockId) {
        return "ev_" + fingerprint(runId + "|block|" + documentId + "|" + blockId);
    }

    private String fingerprint(String value) {
        try {
            String secret = environment.getProperty("math-agent.agent-worker.shared-key", "");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((secret + "|" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Searches only resources visible to the user encoded in the broker request. */
    @PostMapping("/search-visible-resources")
    public Map<String, Object> search(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerSearchRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        TeacherResourceBlockSearchResponse response = resourceSearchService.search(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), request.query(), request.limit(),
                "/internal/agent-tools/v1/search-visible-resources");
        return Map.of("runId", request.runId(), "query", response.query(), "hits", response.hits());
    }

    /** Reads blocks only after the same tenant/role visibility check used for retrieval. */
    @PostMapping("/read-resource-blocks")
    public Map<String, Object> readBlocks(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerReadRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        List<TeacherDocumentBlockResponse> blocks = resourceSearchService.listVisibleBlocks(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), request.documentId());
        return Map.of("runId", request.runId(), "documentId", request.documentId(), "blocks", blocks);
    }

    /**
     * Reads one already-authorized image through Java and returns a self-contained data URL for the visual model.
     * The resource stream is opened only after scope verification; Python receives neither storage key nor local path.
     */
    @PostMapping("/read-resource-asset")
    public Map<String, Object> readAsset(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerAssetRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        try {
            TeacherResourceAssetService.VisibleAsset asset = assetService.openVisibleAsset(
                    request.assetId(), subject);
            long maximumBytes = environment.getProperty("math-agent.agent-worker.asset-max-bytes", Long.class, 0L);
            byte[] content = readBoundedAsset(asset.resource().getInputStream(), maximumBytes);
            String dataUrl = "data:" + asset.mimeType() + ";base64," + Base64.getEncoder().encodeToString(content);
            return Map.of("runId", request.runId(),
                    "asset", Map.of("assetId", asset.assetId(), "mimeType", asset.mimeType(),
                            "fileName", asset.fileName(), "dataUrl", dataUrl));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authorized asset is unavailable", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized asset is unavailable", exception);
        }
    }

    /**
     * Enforces the configured transfer cap while reading rather than after allocation.  The extra byte distinguishes
     * an exact-limit asset from an oversized stream without retaining unbounded source data in Java heap.
     */
    private static byte[] readBoundedAsset(InputStream stream, long maximumBytes) throws IOException {
        if (maximumBytes <= 0L || maximumBytes > Integer.MAX_VALUE - 1L) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Agent asset limit is invalid");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream((int) maximumBytes)) {
            byte[] buffer = new byte[ASSET_READ_BUFFER_BYTES];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Authorized asset exceeds configured Agent tool limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void authorize(String actual) {
        String expected = environment.getProperty("math-agent.agent-worker.shared-key", "");
        if (expected.isBlank() || actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent Worker key is invalid");
        }
    }

    /**
     * Resolves identity from the durable run row instead of trusting model-generated tenant fields. The request
     * fields remain in the DTO for wire compatibility, but production routes fail closed when the run is unknown or
     * the caller tries to use a different identity. The null-store branch exists only for focused direct-controller
     * tests that intentionally bypass Spring persistence wiring.
     */
    private RequestSubject subjectForRun(String runId, String suppliedTenantId, String suppliedSubjectType, String suppliedSubjectId) {
        if (agentTraceStore != null) {
            AgentTraceRecord trace = agentTraceStore.find(runId == null ? "" : runId.strip()).orElse(null);
            if (trace != null) {
                RequestSubject persisted = new RequestSubject(
                        trace.tenantId(), trace.subjectType(), trace.subjectId(), "agent-worker").normalize();
                requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
                return persisted;
            }
        }
        if (workflowStore != null) {
            MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId).orElse(null);
            if (workflow != null) {
                RequestSubject persisted = new RequestSubject(
                        workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
                requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
                return persisted;
            }
        }
        if (agentTraceStore == null && workflowStore == null) {
            return new RequestSubject(suppliedTenantId, suppliedSubjectType, suppliedSubjectId, "agent-worker").normalize();
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run authorization not found");
    }

    /** Allows absent legacy wire fields while rejecting any supplied identity that differs from the durable run. */
    private static void requireMatchingSuppliedIdentity(
            RequestSubject persisted, String tenantId, String subjectType, String subjectId) {
        boolean supplied = tenantId != null || subjectType != null || subjectId != null;
        if (supplied && (!persisted.tenantId().equals(tenantId == null ? "" : tenantId.strip())
                || !persisted.subjectType().equals(subjectType == null ? "" : subjectType.strip())
                || !persisted.subjectId().equals(subjectId == null ? "" : subjectId.strip()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent run identity does not match its durable authorization");
        }
    }

}
