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
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
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
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    private final Environment environment;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final TeachingTaskStore teachingTaskStore;
    private final AgentTraceStore agentTraceStore;

    @Autowired
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            TextbookAuthorizedBlockReader textbookBlockReader,
            CanonicalMathPaperAuthorizedBlockReader canonicalPaperBlockReader,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this.resourceSearchService = resourceSearchService;
        this.assetService = assetService;
        this.textbookBlockReader = textbookBlockReader;
        this.canonicalPaperBlockReader = canonicalPaperBlockReader;
        this.environment = environment;
        this.workflowStore = workflowStore;
        this.teachingTaskStore = teachingTaskStore;
        this.agentTraceStore = agentTraceStore;
    }

    /** Keeps direct textbook-reader tests source-compatible while production also injects canonical publication access. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            TextbookAuthorizedBlockReader textbookBlockReader,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this(resourceSearchService, assetService, textbookBlockReader, null, environment, workflowStore,
                teachingTaskStore, agentTraceStore);
    }

    /** Keeps older direct tests source-compatible while production injects both durable run stores. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore) {
        this(resourceSearchService, assetService, null, null, environment, workflowStore, null, null);
    }

    /** Compatibility constructor for direct tests with durable teaching-task storage. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this(resourceSearchService, assetService, null, null, environment, workflowStore, teachingTaskStore, agentTraceStore);
    }

    /** Compatibility constructor for focused broker tests that do not load workflow persistence. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment) {
        this(resourceSearchService, assetService, null, null, environment, null, null, null);
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
        List<com.doob.mathagent.teaching.TeachingEvidence> authorized = request.evidenceRefs() == null
                || request.evidenceRefs().isEmpty()
                ? List.of()
                : authorizedHandoutEvidence(request.runId(), request.evidenceRefs());
        List<Map<String, Object>> items = authorized.stream()
                // A stale source is excluded only when it previously advertised an expandable document capability.
                .filter(evidence -> isContextEvidenceVisible(evidence, subject))
                .limit(request.limit())
                .map(evidence -> Map.<String, Object>of(
                        "ref", evidenceRef(request.runId(), evidence),
                        "title", evidence.sourceTitle(),
                        "documentName", evidence.sourceTitle(),
                        "documentRef", hasDocumentReferenceCandidate(evidence) && isContextEvidenceVisible(evidence, subject)
                                ? documentRef(request.runId(), evidence.sourceDocumentId()) : "",
                        "excerpt", compactEvidence(evidence.snippet(), ""),
                        "assetIds", evidence.assetIds(),
                        "assetId", evidence.assetIds().isEmpty() ? "" : evidence.assetIds().getFirst()))
                .toList();
        auditHandoutInspection(request.runId(), "context", "", items.size());
        return Map.of("runId", request.runId(), "items", items);
    }

    /**
     * Returns original parsed blocks from exactly one RAG-authorized source document. The durable task is the
     * authorization authority: a syntactically valid opaque reference for another document is rejected before read.
     */
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
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), visibleBlocks, maxBlocks, maxChars);
        auditHandoutInspection(request.runId(), "read", request.documentRef(), blocks.size());
        return Map.of("runId", request.runId(), "documentRef", request.documentRef(), "blocks", blocks);
    }

    @PostMapping("/handout-canonical-question-read")
    public Map<String, Object> handoutCanonicalQuestionRead(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutCanonicalQuestionReadRequest request) {
        authorize(workerKey);
        subjectForHandoutRun(request.runId());
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
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), visibleBlocks, maxBlocks, maxChars);
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
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), visibleBlocks, maxBlocks, maxChars);
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
        List<Map<String, Object>> blocks = compactBlocks(request.runId(), matches, maxBlocks, maxChars);
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
        List<Map<String, Object>> items = response.hits().stream().map(hit -> Map.<String, Object>of(
                "ref", evidenceRef(request.runId(), teacherEvidence(hit)),
                "title", hit.documentTitle() == null ? "" : hit.documentTitle(),
                "documentName", hit.documentTitle() == null ? "" : hit.documentTitle(),
                "documentRef", documentRef(request.runId(), hit.documentId()),
                "excerpt", compactEvidence(hit.evidenceText(), hit.snippet()),
                "assetIds", hit.imageAssetIds() == null ? List.of() : hit.imageAssetIds(),
                "assetId", hit.assetRefs() == null || hit.assetRefs().isEmpty()
                        || hit.assetRefs().getFirst().assetId() == null ? "" : hit.assetRefs().getFirst().assetId())).toList();
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

    /** Bounds evidence sent over the single Java-Python context request. */
    private static String compactEvidence(String evidenceText, String snippet) {
        String value = evidenceText == null || evidenceText.isBlank() ? snippet : evidenceText;
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 3000 ? normalized : normalized.substring(0, 2997) + "...";
    }

    /** Emits metadata-only audit records; source text, paths, URLs, query text and credentials never enter logs. */
    private void auditHandoutInspection(String runId, String operation, String opaqueReference, int resultCount) {
        LOGGER.info("handout_document_inspection runId={} operation={} referenceFingerprint={} resultCount={}",
                runId, operation, fingerprint(opaqueReference), resultCount);
    }

    private TeachingEvidence authorizedHandoutDocumentEvidence(String runId, String opaqueReference) {
        TeachingTaskResponse task = teachingTaskStore == null ? null
                : teachingTaskStore.findByTaskId(runId).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
        }
        return (task.evidence() == null ? List.<TeachingEvidence>of() : task.evidence()).stream()
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
    /** Creates the exact persisted ledger representation so returned opaque refs remain reloadable by handout-context. */
    private TeachingEvidence teacherEvidence(TeacherResourceBlockSearchResponse.Hit hit) {
        return new TeachingEvidence(
                "TEACHER_RESOURCE",
                hit.documentTitle() == null ? "" : hit.documentTitle(),
                hit.blockId(),
                hit.pageNo() == null ? 0 : hit.pageNo(),
                compactEvidence(hit.evidenceText(), hit.snippet()),
                "", "", hit.documentId(),
                hit.sourceType() == null ? "teacher_resource" : hit.sourceType(),
                "", "", hit.imageAssetIds() == null ? List.of() : hit.imageAssetIds());
    }

    private void persistTeacherSearchEvidence(
            String runId, RequestSubject subject, List<TeacherResourceBlockSearchResponse.Hit> hits) {
        if (teachingTaskStore == null || hits == null || hits.isEmpty()) {
            return;
        }
        TeachingTaskResponse task = teachingTaskStore.findByTaskId(runId).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
        }
        List<TeachingEvidence> merged = new java.util.ArrayList<>(
                task.evidence() == null ? List.of() : task.evidence());
        java.util.Set<String> known = merged.stream()
                .map(evidence -> evidence.sourceScope() + "|" + evidence.sourceDocumentId() + "|" + evidence.chunkId())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (TeacherResourceBlockSearchResponse.Hit hit : hits) {
            if (hit == null || hit.documentId() == null || hit.documentId().isBlank()
                    || hit.blockId() == null || hit.blockId().isBlank()) {
                continue;
            }
            String key = "TEACHER_RESOURCE|" + hit.documentId() + "|" + hit.blockId();
            if (!known.add(key)) {
                continue;
            }
            merged.add(teacherEvidence(hit));
        }
        if (merged.size() == (task.evidence() == null ? 0 : task.evidence().size())) {
            return;
        }
        String ownerKey = subject.tenantId() + ":" + subject.subjectType() + ":" + subject.subjectId();
        teachingTaskStore.save(ownerKey, ownerKey + ":" + task.clientRequestId(), task.withEvidence(merged));
    }

    /** 仅将任务持久化证据中、且由本运行签发的引用还原为模型可见的证据块。 */
    private List<com.doob.mathagent.teaching.TeachingEvidence> authorizedHandoutEvidence(
            String runId, List<String> opaqueReferences) {
        TeachingTaskResponse task = teachingTaskStore == null ? null
                : teachingTaskStore.findByTaskId(runId).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
        }
        if (opaqueReferences == null || opaqueReferences.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Handout evidence references are required");
        }
        List<com.doob.mathagent.teaching.TeachingEvidence> durable = task.evidence() == null
                ? List.of() : task.evidence();
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

    private List<Map<String, Object>> compactBlocks(String runId, List<TeacherDocumentBlockResponse> source, int maxBlocks, int maxChars) {
        int remaining = maxChars;
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (TeacherDocumentBlockResponse block : source) {
            if (result.size() >= maxBlocks || remaining <= 0) break;
            String text = compactEvidence(block.rawText(), block.normalizedText());
            // 保留完整解析块，避免下游提示词把一个已授权证据截成无上下文的半句。
            if (text.isBlank() || text.length() > remaining) continue;
            result.add(Map.of("ref", blockEvidenceRef(runId, block.documentId(), block.blockId()), "text", text,
                    "section", block.section() == null ? "" : block.section(), "pageNo", block.pageNo() == null ? 0 : block.pageNo()));
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
        return "ev_" + fingerprint(runId + "|evidence|" + evidence.sourceDocumentId() + "|"
                + evidence.sourceScope() + "|" + evidence.sourceTitle() + "|" + evidence.chunkId());
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
            AgentTraceRecord trace = agentTraceStore.find(runId == null ? "" : runId.strip())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run authorization not found"));
            RequestSubject persisted = new RequestSubject(
                    trace.tenantId(), trace.subjectType(), trace.subjectId(), "agent-worker").normalize();
            requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
            return persisted;
        }
        if (workflowStore == null) {
            return new RequestSubject(suppliedTenantId, suppliedSubjectType, suppliedSubjectId, "agent-worker").normalize();
        }
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run authorization not found"));
        RequestSubject persisted = new RequestSubject(
                workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
        requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
        return persisted;
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
