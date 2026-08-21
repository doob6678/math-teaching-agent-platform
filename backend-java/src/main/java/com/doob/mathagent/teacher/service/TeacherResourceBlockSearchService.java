package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookPageImageSearchHit;
import com.doob.mathagent.retrieval.TextbookPageImageSearchRequest;
import com.doob.mathagent.retrieval.TextbookPageImageSearchResponse;
import com.doob.mathagent.retrieval.TextbookPageImageSearchService;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.search.TeacherResourceSearchProperties;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceReadiness;
import com.doob.mathagent.teacher.document.TeacherResourceVisibilityPolicy;
import com.doob.mathagent.teacher.support.TeacherResourceLibraryResolver;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorSearchFilter;
import com.doob.mathagent.vector.service.VectorSearchHit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Searches parsed teacher resource blocks with backend-controlled tenant and scope visibility.
 *
 * <p>The default path is an explicit two-stage retriever:</p>
 *
 * <ol>
 *     <li>Aggregate vector + lexical evidence at document level so the right document is more likely to enter the
 *     candidate set even when several sibling blocks look similar.</li>
 *     <li>Rerank blocks only inside those candidate documents using block role, headings, source path, graph tags,
 *     and neighboring evidence windows.</li>
 * </ol>
 *
 * <p>Production now exposes only one retrieval implementation so document recall and in-document block rerank stay
 * single-sourced across teacher search, agent tools, and textbook merge paths.</p>
 */
@Service
public class TeacherResourceBlockSearchService {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final Logger log = LoggerFactory.getLogger(TeacherResourceBlockSearchService.class);
    static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    static final String STRATEGY_TWO_STAGE_DOC_BLOCK = "two_stage_doc_block";
    static final int MIN_TITLE_RECALL_TERM_LENGTH = 2;
    static final Pattern VISUAL_EVIDENCE_QUERY_PATTERN = Pattern.compile(
            "(?:图|图片|如图|地图|image|figure)", Pattern.CASE_INSENSITIVE);
    static final Pattern QUERY_CLAUSE_SPLITTER = Pattern.compile("[\\r\\n,，。；;：:！？!?()（）\\[\\]【】]+");
    /**
     * Feishu document tokens survive title edits and distinguish two otherwise similarly named teaching handouts.
     * The resolver uses them only as one corroborating signal; a token alone never selects a block.
     */
    static final Pattern STABLE_SOURCE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{11,})(?![A-Za-z0-9])");
    /** Numbered source stems are the only safe boundary for associating an adjacent DOCX diagram. */
    static final Pattern TOP_LEVEL_QUESTION_NUMBER = Pattern.compile(
            "(?m)^\\h*(\\d{1,2})[.．、]\\h*");
    /** Keep the association local: an image beyond this many source blocks is not reliably question-owned. */
    static final int MAX_INLINE_FIGURE_LOOKAHEAD_BLOCKS = 3;
    /**
     * A text paragraph and its Feishu image are emitted as separate blocks.  Retrieval ranks text, so bind only a
     * nearby image block from the same document instead of silently dropping the visual evidence from the hit.
     */
    static final int MAX_NEARBY_IMAGE_BLOCK_DISTANCE = 4;
    /** A single evidence hit should not flood MCP/model context with every decorative image in a document. */
    static final int MAX_IMAGE_ASSETS_PER_HIT = 2;
    /** Scores below this level without any query-term support are treated as generic corpus noise. */
    static final double MINIMUM_SEMANTIC_SUPPORT_SCORE = 0.30d;

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceBlockSearchAuditSink auditSink;
    private final VectorIndexService vectorIndexService;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TeacherResourceAssetService assetService;
    private final TextbookRetrievalService textbookRetrievalService;
    private final TextbookPageImageSearchService textbookPageImageSearchService;
    private final TextbookResourceProperties textbookResourceProperties;
    private final TeacherResourceSearchProperties searchProperties;
    private final TeacherResourceSearchProperties.QueryFocusBudget queryFocusBudget;
    private final TeacherResourceSearchProperties.SearchRuntimeBudget searchBudget;
    private TeacherSourceFileReader sourceFileReader;

    /**
     * Creates a parsed block search service.
     *
     * @param resourceStore source document store
     * @param blockStore parsed document block store
     * @param auditSink recent audit sink for UI and MCP queryId lookup
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                TeacherResourceSearchProperties.defaults(),
                TeacherResourceGraphAlignmentService.disabled(),
                TeacherResourceAssetService.disabled(),
                null,
                null,
                null);
    }

    /** Returns the opaque Milvus evidence reference without a second document lookup or visibility decision. */
    public Optional<CanonicalReference> resolveVisibleReference(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceBlockSearchResponse.Hit hit) {
        if (hit == null || hit.documentId() == null || hit.documentId().isBlank()
                || hit.blockId() == null || hit.blockId().isBlank()) {
            return Optional.empty();
        }
        requireText(tenantId, "tenantId is required");
        requireText(viewerRole, "viewerRole is required");
        requireText(viewerSubjectId, "viewerSubjectId is required");
        return Optional.of(new CanonicalReference(
                hit.documentId(), hit.blockId(), textOrDefault(hit.documentTitle(), ""), ""));
    }
    /**
     * Production constructor with graph-aware query normalization.
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService,
            TextbookRetrievalService textbookRetrievalService,
            TextbookPageImageSearchService textbookPageImageSearchService,
            TextbookResourceProperties textbookResourceProperties) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                TeacherResourceSearchProperties.defaults(),
                graphAlignmentService,
                assetService,
                textbookRetrievalService,
                textbookPageImageSearchService,
                textbookResourceProperties);
    }

    @Autowired
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceSearchProperties searchProperties,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService,
            TextbookRetrievalService textbookRetrievalService,
            TextbookPageImageSearchService textbookPageImageSearchService,
            TextbookResourceProperties textbookResourceProperties) {
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore is required");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
        this.searchProperties = Objects.requireNonNull(searchProperties, "searchProperties is required");
        this.queryFocusBudget = this.searchProperties.queryFocus();
        this.searchBudget = this.searchProperties.runtime();
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.assetService = Objects.requireNonNull(assetService, "assetService is required");
        this.textbookRetrievalService = textbookRetrievalService;
        this.textbookPageImageSearchService = textbookPageImageSearchService;
        this.textbookResourceProperties = textbookResourceProperties;
    }

    /** File-backed detail reads are injected separately so focused search tests do not need storage wiring. */
    @Autowired(required = false)
    public void setSourceFileReader(TeacherSourceFileReader sourceFileReader) {
        this.sourceFileReader = sourceFileReader;
    }

    /**
     * Reports whether an indexed teacher document still has a readable catalog-backed source root.
     *
     * <p>This remains a backend-only integrity check. It returns no path or source text and lets callers exclude
     * stale vector candidates before they become RAG evidence.</p>
     */
    public boolean isSourceAvailable(String tenantId, String documentId) {
        return sourceFileReader != null && sourceFileReader.isSourceAvailable(tenantId, documentId);
    }

    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                TeacherResourceSearchProperties.defaults(),
                graphAlignmentService,
                assetService,
                null,
                null,
                null);
    }

    /**
     * Backward-compatible constructor kept for focused tests that only provide graph alignment.
     */
    public TeacherResourceBlockSearchService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceBlockSearchAuditSink auditSink,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService) {
        this(
                resourceStore,
                blockStore,
                auditSink,
                vectorIndexService,
                TeacherResourceSearchProperties.defaults(),
                graphAlignmentService,
                TeacherResourceAssetService.disabled(),
                null,
                null,
                null);
    }

    /**
     * Searches active parsed blocks visible to the backend-resolved teacher/admin subject.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved subject id
     * @param query user search query
     * @param limit requested maximum hit count
     * @return visible block search response
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        return search(tenantId, viewerRole, viewerSubjectId, query, limit, "/api/teacher/resources/search");
    }

    /**
     * Searches visible blocks and records the logical caller endpoint for audit correlation.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved subject id
     * @param query user search query
     * @param limit requested maximum hit count
     * @param endpoint logical API endpoint or MCP tool call path
     * @return visible block search response
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit,
            String endpoint) {
        return search(

                tenantId,
                viewerRole,
                viewerSubjectId,
                query,
                limit,
                endpoint,
                TeacherResourceSearchFilter.EMPTY);
    }

    /**
     * Searches visible blocks and applies optional metadata filters.
     */
    public TeacherResourceBlockSearchResponse search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit,
            String endpoint,
            TeacherResourceSearchFilter filter) {
        long startedNanos = System.nanoTime();
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireReaderRole(normalizedRole);
        String normalizedQuery = normalizeQuery(query);
        int safeLimit = clampLimit(limit);
        TeacherResourceSearchFilter normalizedFilter = normalizeFilter(filter, normalizedQuery);
        if (normalizedQuery.isBlank()) {
            TeacherResourceBlockSearchResponse emptyResponse = response(
                    normalizedQuery,
                    safeLimit,
                    STRATEGY_TWO_STAGE_DOC_BLOCK + "_empty",
                    List.of());
            recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, emptyResponse, startedNanos);
            return emptyResponse;
        }
        // Resolve the query against the authenticated viewer's curated graph before Milvus recall. This adds only
        // canonical ids/tags to the focused query; evidence and visibility still come exclusively from real retrieval.
        TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph = graphAlignmentService.alignQuery(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                normalizedQuery);
        FocusedSearchQuery focusedQuery = focusedQuery(normalizedQuery, queryGraph);
        boolean includeRealTextbook = shouldUseRealTextbook(normalizedFilter);
        TeacherResourceBlockSearchResponse searchResponse;
        if (includeRealTextbook && isTextbookOnlyFilter(normalizedFilter)) {
            /*
             * When the caller explicitly pins the library to textbook, searching teacher-resource rows first only
             * adds generic local_path noise and wastes rerank budget on a corpus that can never satisfy the filter.
             * Go straight to the dedicated textbook retriever, then keep the normal merge/output path below.
             */
            searchResponse = response(
                    normalizedQuery,
                    safeLimit,
                    retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, normalizedFilter, "textbook_only"),
                    List.of());
        } else {
            searchResponse = twoStageResponse(
                    normalizedTenantId,
                    normalizedQuery,
                    focusedQuery,
                    safeLimit,
                    normalizedFilter);
        }
        if (includeRealTextbook) {
            searchResponse = mergeRealTextbookHits(
                    searchResponse,
                    normalizedTenantId,
                    normalizedRole,
                    normalizedSubjectId,
                    normalizedQuery,
                    focusedQuery,
                    safeLimit,
                    normalizedFilter,
                    endpoint);
        }
        recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, searchResponse, startedNanos);
        return searchResponse;
    }

    private TeacherResourceBlockSearchResponse twoStageResponse(
            String tenantId,
            String normalizedQuery,
            FocusedSearchQuery focusedQuery,
            int safeLimit,
            TeacherResourceSearchFilter filter) {
        VectorCoarseRecall vectorCoarseRecall = vectorCoarseRecall(
                tenantId, focusedQuery.semanticQuery(), safeLimit, filter);
        // A source file can contain several independently useful questions.  Preserve every Milvus block hit
        // instead of collapsing by sourcePath, otherwise a query can silently lose distinct questions from one paper.
        List<TeacherResourceBlockSearchResponse.Hit> hits = vectorCoarseRecall.hits().stream()
                // A stale vector must never make unavailable raw teacher text model-visible. The catalog is the same
                // authorization-backed source used by document reads, so this cannot be repaired with a DB fallback.
                .filter(hit -> sourceFileReader == null
                        || sourceFileReader.isSourceAvailable(tenantId, hit.documentId()))
                .limit(safeLimit)
                .map(hit -> milvusHit(hit, normalizedQuery, focusedQuery.terms()))
                .toList();
        return response(normalizedQuery, safeLimit,
                retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "milvus_evidence_only"), hits);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean titleMatchesQuery(String title, String normalizedQuery, String[] focusedTerms) { return TeacherResourceBlockSearchPolicy.titleMatchesQuery(title, normalizedQuery, focusedTerms); }

    /**
     * Stage one now loads block payload lazily. When vector coarse recall already narrowed the plausible document set,
     * do not fetch parsed blocks for every visible teacher resource up front. Only the candidate documents are loaded
     * into memory, and a full-corpus block scan is kept as a one-time fallback when semantic admission finds nothing.
     */
    private Map<String, List<BlockContext>> stageOneBlockContexts(
            String tenantId,
            Map<String, TeacherResourceDocumentResponse> documentsById,
            List<String> visibleDocumentIds,
            List<String> semanticCandidateDocumentIds,
            List<String> tags) {
        /*
         * A narrow, explicit library can contain only a few documents while each older document contributes many
         * vectors. Restricting that library to the global vector Top-N lets sibling blocks crowd a newly uploaded
         * document out before the single BGE rerank is reached. When the complete library fits the configured
         * document window, admit every visible document and let the bounded block rerank decide the final order.
         */
        Map<String, List<BlockContext>> allFileContexts =
                loadVisibleBlockContexts(tenantId, documentsById, visibleDocumentIds, tags);
        if (visibleDocumentIds != null
                && visibleDocumentIds.size() <= stageDocumentCandidateLimit(visibleDocumentIds.size())) {
            return allFileContexts;
        }
        if (semanticCandidateDocumentIds == null || semanticCandidateDocumentIds.isEmpty()) {
            return allFileContexts;
        }
        Set<String> candidateParentKeys = new LinkedHashSet<>(semanticCandidateDocumentIds);
        return allFileContexts.entrySet().stream()
                .filter(entry -> candidateParentKeys.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, ignored) -> left,
                        LinkedHashMap::new));
    }

    /**
     * Merges real textbook hits into the teacher-facing response shape. Mixed queries also need this path because the
     * teacher store no longer carries textbook derivative rows once processed_books is available; otherwise a search
     * without `library=textbook` would silently lose textbook recall.
     */
    private TeacherResourceBlockSearchResponse mergeRealTextbookHits(
            TeacherResourceBlockSearchResponse teacherResponse,
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String normalizedQuery,
            FocusedSearchQuery focusedQuery,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            String endpoint) {
        if (textbookRetrievalService == null || textbookResourceProperties == null) {
            return teacherResponse;
        }
        TextbookSearchResponse textbookResponse = textbookRetrievalService.search(
                textbookResourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(
                        focusedQuery.semanticQuery(),
                        safeLimit,
                        filter == null ? List.of() : filter.documentIds()),
                new RetrievalRequestContext(
                        tenantId,
                        viewerRole,
                        viewerSubjectId,
                        null,
                        null,
                        "teacher-resource-search",
                        endpoint));
        List<TeacherResourceBlockSearchResponse.Hit> textbookTextHits = textbookResponse.hits().stream()
                .map(TeacherResourceBlockSearchService::textbookHit)
                .toList();
        List<String> candidateDocIds = textbookResponse.hits().stream()
                .map(TextbookSearchHit::docId)
                .filter(docId -> docId != null && !docId.isBlank())
                .distinct()
                .toList();
        /*
         * CLIP page search is a multimodal fallback, not the default path for every text query. When textbook text
         * hits already exist, forcing page-image retrieval and a second cross-source rerank adds large latency while
         * rarely changing the winner. Keep the image path for the real failure mode: text recall missed but the page
         * image itself may still be distinctive.
         */
        List<TeacherResourceBlockSearchResponse.Hit> textbookImageHits = textbookTextHits.isEmpty()
                ? textbookImageHits(focusedQuery.semanticQuery(), safeLimit, candidateDocIds)
                : List.of();
        boolean teacherHitsEmpty = teacherResponse == null || teacherResponse.hits() == null || teacherResponse.hits().isEmpty();
        if (teacherHitsEmpty && textbookImageHits.isEmpty() && !textbookTextHits.isEmpty()) {
            return new TeacherResourceBlockSearchResponse(
                    teacherResponse == null ? UUID.randomUUID().toString() : teacherResponse.queryId(),
                    normalizedQuery,
                    safeLimit,
                    safeRetrievalMode(buildTextbookMergeMode(teacherResponse, textbookResponse, false) + "_text_only"),
                    textbookTextHits.size(),
                    textbookTextHits.stream().limit(safeLimit).toList());
        }
        List<TeacherResourceBlockSearchResponse.Hit> combinedHits = new ArrayList<>();
        combinedHits.addAll(textbookTextHits);
        combinedHits.addAll(textbookImageHits);
        if (teacherResponse != null && teacherResponse.hits() != null) {
            combinedHits.addAll(teacherResponse.hits());
        }
        boolean explicitMixedLibraries = isExplicitMixedLibraryFilter(filter);
        int mergeCandidateLimit = explicitMixedLibraries ? Math.multiplyExact(safeLimit, 2) : safeLimit;
        List<TeacherResourceBlockSearchResponse.Hit> mergedHits =
                semanticMergeHits(focusedQuery.semanticQuery(), focusedQuery.terms(), combinedHits, mergeCandidateLimit);
        if (explicitMixedLibraries) {
            // A mixed request is deliberately asking for two corpora. Reserve result capacity for teacher evidence so
            // a large public textbook corpus cannot evict every uploaded document after the shared BGE rerank.
            mergedHits = applyCrossSourceQuota(mergedHits, safeLimit);
        }
        if (mergedHits.isEmpty()) {
            return teacherResponse;
        }
        return new TeacherResourceBlockSearchResponse(
                teacherResponse == null ? UUID.randomUUID().toString() : teacherResponse.queryId(),
                normalizedQuery,
                safeLimit,
                safeRetrievalMode(buildTextbookMergeMode(teacherResponse, textbookResponse, !textbookImageHits.isEmpty())),
                mergedHits.size(),
                mergedHits);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean isExplicitMixedLibraryFilter(TeacherResourceSearchFilter filter) { return TeacherResourceBlockSearchPolicy.isExplicitMixedLibraryFilter(filter); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<TeacherResourceBlockSearchResponse.Hit> applyCrossSourceQuota(List<TeacherResourceBlockSearchResponse.Hit> rankedHits, int limit) { return TeacherResourceBlockSearchPolicy.applyCrossSourceQuota(rankedHits, limit); }

    /**
     * Textbook search now has two real evidence routes: processed_books text chunks and the worker-maintained CLIP page
     * index. We merge both before the final teacher-search ranking so text-heavy pages and image-distinct pages can
     * compete in the same semantic space instead of relying on hand-written cross-source score scaling.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> textbookImageHits(
            String normalizedQuery,
            int safeLimit,
            List<String> candidateDocIds) {
        if (textbookPageImageSearchService == null) {
            return List.of();
        }
        try {
            TextbookPageImageSearchResponse response = textbookPageImageSearchService.search(
                    new TextbookPageImageSearchRequest(normalizedQuery, null, safeLimit, candidateDocIds == null ? List.of() : candidateDocIds));
            return response.hits().stream()
                    .map(TeacherResourceBlockSearchService::textbookImageHit)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_textbook_image_fallback query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return List.of();
        }
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String buildTextbookMergeMode(TeacherResourceBlockSearchResponse teacherResponse, TextbookSearchResponse textbookResponse, boolean usedImageRoute) { return TeacherResourceBlockSearchPolicy.buildTextbookMergeMode(teacherResponse, textbookResponse, usedImageRoute); }

    /**
     * Loads active blocks once and keeps normalized metadata alongside them so stage one and stage two share the same
     * real parsed source state.
     */
    private Map<String, List<BlockContext>> loadVisibleBlockContexts(
            String tenantId,
            Map<String, TeacherResourceDocumentResponse> documentsById,
            List<String> documentIds,
            List<String> tags) {
        Map<String, List<BlockContext>> contexts = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return contexts;
        }
        Map<String, List<TeacherDocumentBlockResponse>> blocksByDocumentId = blockStore.listByDocuments(tenantId, documentIds);
        for (String documentId : documentIds) {
            TeacherResourceDocumentResponse document = documentsById.get(documentId);
            if (document == null) {
                continue;
            }
            List<TeacherDocumentBlockResponse> documentBlocks = blocksByDocumentId.getOrDefault(documentId, List.of());
            boolean documentHasFileIdentity = documentBlocks.stream()
                    .anyMatch(block -> block.sourcePath() != null && !block.sourcePath().isBlank());
            for (TeacherDocumentBlockResponse block : documentBlocks) {
                if (!matchesTags(document, block, tags)) {
                    continue;
                }
                BlockContext context = toContext(document, block);
                String parentKey = documentHasFileIdentity
                        ? fileParentKey(document.documentId(), "", context.sourcePath(), block.blockId())
                        : document.documentId() + "::legacy-missing-source-path";
                if (context.sourcePath().isBlank()) {
                    log.warn("teacher_resource_data_quality_missing_file_identity tenantId={} documentId={} blockId={}",
                            tenantId, document.documentId(), block.blockId());
                }
                contexts.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(context);
            }
        }
        contexts.replaceAll((parentKey, blocks) -> blocks.stream()
                .sorted(Comparator.comparingInt(block -> block.block().blockOrder()))
                .toList());
        return contexts;
    }

    /**
     * Stage one groups vector hits by document instead of consuming them directly as final block hits. This is the
     * main behavioral change: vector search helps decide which document is plausible, then stage two resolves the
     * correct block inside that document.
     */
    private VectorCoarseRecall vectorCoarseRecall(
            String tenantId,
            String normalizedQuery,
            int safeLimit,
            TeacherResourceSearchFilter filter) {
        int vectorCandidateLimit = searchBudget.vectorCandidateLimit(safeLimit, safeLimit);
        List<VectorSearchHit> hits;
        try {
            hits = vectorIndexService.searchTeacherResourceBlocks(
                    normalizedQuery,
                    vectorCandidateLimit,
                    new VectorSearchFilter(
                            List.of(tenantId),
                            filter == null ? List.of() : filter.documentIds(),
                            filter == null ? List.of() : filter.permissionScopes(),
                            filter == null ? List.of() : filter.sourceTypes()));
        } catch (RuntimeException exception) {
            log.error("teacher_resource_search_vector_failed strategy=two_stage query={} documentCandidates={} message={}",
                    normalizedQuery,
                    0,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            throw new IllegalStateException("Teacher resource vector retrieval failed", exception);
        }
        return new VectorCoarseRecall(List.copyOf(hits));
    }

    /** Converts a Milvus hit directly to model evidence; no document/block/asset database read is allowed here. */
    private TeacherResourceBlockSearchResponse.Hit milvusHit(
            VectorSearchHit hit, String normalizedQuery, String[] terms) {
        String text = textOrDefault(hit.text(), "");
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.documentId(), "", "", "", hit.blockId(), "", 0, "", "", null,
                hit.sourcePath(), "reference", List.of(), List.of(hit.blockId()), text,
                snippet(text, normalizedQuery, terms), hit.score(), List.of(), List.of());
    }

    private List<DocumentCandidate> rerankedDocumentCandidates(
            Map<String, TeacherResourceDocumentResponse> documentsById,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<DocumentCandidate> coarseCandidates = coarseDocumentCandidates(
                documentsById,
                blocksByDocumentId,
                vectorScoreByKey,
                normalizedQuery,
                terms,
                safeLimit,
                queryGraph);
        if (coarseCandidates.isEmpty()) {
            return coarseCandidates;
        }
        /*
         * Stage one is document-level coarse recall, not a second expensive final rank. Milvus semantic recall plus
         * lexical rescue has already admitted and ordered plausible documents. Calling the cross-encoder here and
         * again for blocks duplicates the query/model work while the only user-visible question is which in-document
         * evidence block is correct. Keep the coarse document order and reserve the real reranker for stage two.
         */
        return coarseCandidates.stream()
                .limit(searchBudget.documentRerankCandidateLimit(coarseCandidates.size()))
                .toList();
    }

    private List<DocumentCandidate> coarseDocumentCandidates(
            Map<String, TeacherResourceDocumentResponse> documentsById,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            int safeLimit,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        List<DocumentCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<BlockContext>> entry : blocksByDocumentId.entrySet()) {
            TeacherResourceDocumentResponse document = documentsById.get(entry.getKey());
            if (document == null) {
                continue;
            }
            List<BlockContext> blocks = entry.getValue();
            List<BlockContext> supportedBlocks = supportedBlocks(
                    document,
                    blocks,
                    vectorScoreByKey,
                    normalizedQuery,
                    terms);
            double bestVector = 0.0d;
            for (BlockContext block : supportedBlocks) {
                String key = blockKey(document.documentId(), block.block().blockId());
                double vectorScore = vectorScoreByKey.getOrDefault(key, 0.0d);
                bestVector = Math.max(bestVector, vectorScore);
            }
            if (supportedBlocks.isEmpty()) {
                continue;
            }
            candidates.add(new DocumentCandidate(
                    document,
                    supportedBlocks,
                    bestVector));
        }
        return candidates.stream()
                .sorted(documentCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(stageDocumentCandidateLimit(candidates.size()))
                .toList();
    }

    /**
     * Two-stage rerank does not need every block from a long document. We keep only the strongest block-level support
     * signals per document, bounded by the caller's requested limit, so the real rerank model spends capacity on the
     * most plausible evidence instead of timing out on hundreds of weak siblings from the same file.
     */
    private List<BlockContext> supportedBlocks(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        boolean documentHasSemanticCandidates = blocks.stream().anyMatch(block ->
                vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d) > 0.0d);
        if (documentHasSemanticCandidates) {
            /*
             * Formula-heavy teacher material frequently has a short exact lexical anchor that BGE places outside
             * the global Milvus Top-N.  The old path discarded that block as soon as any semantic candidate existed,
             * so a correct formula or named theorem could never reach the cross-encoder.  Keep the semantic set and
             * add a small, deterministic lexical-rescue set from the same visible document.  This is an admission
             * rule, not a cross-score weight: the final order still belongs to the configured reranker.
             */
            LinkedHashMap<String, BlockContext> admitted = new LinkedHashMap<>();
            blocks.stream()
                    .filter(block -> vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d) > 0.0d)
                    .sorted(blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms))
                    .forEach(block -> admitted.put(block.block().blockId(), block));
            if (searchBudget.lexicalRescueEnabled()) {
                blocks.stream()
                        .filter(block -> blockLexicalMatchCount(document, block, normalizedQuery, terms) > 0)
                        .sorted(Comparator.comparingInt(
                                        (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms))
                                .reversed()
                                .thenComparingInt(block -> block.block().blockOrder()))
                        .limit(searchBudget.maxLexicalRescueBlocksPerDocument())
                        .forEach(block -> admitted.putIfAbsent(block.block().blockId(), block));
            }
            Comparator<BlockContext> admissionComparator = Comparator.comparingInt(
                            (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms))
                    .reversed()
                    .thenComparing(blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms));
            return admitted.values().stream().sorted(admissionComparator).toList();
        }
        /*
         * Absence from Milvus is an indexing consistency fault, not a reason to generate request-time embeddings for
         * every block. The bounded lexical selector only prepares persisted candidates for the dedicated GPU reranker.
         */
        return blocks.stream()
                .filter(block -> blockLexicalMatchCount(document, block, normalizedQuery, terms) > 0)
                .sorted(blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms))
                .limit(searchBudget.maxBlocksPerDocumentForStageTwo())
                .toList();
    }

    /**
     * Stage two reranks only inside candidate documents. This avoids the old failure mode where one near-duplicate
     * sibling block from an unrelated document beats the correct local neighbor block from the right document.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> rerankedBlockHits(
            List<DocumentCandidate> rankedDocuments,
            Map<String, List<BlockContext>> blocksByDocumentId,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms,
            String roleIntentQuery,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        Map<String, Double> semanticScoreByKey = semanticScoreByKey(
                rankedDocuments,
                blocksByDocumentId,
                normalizedQuery);
        /*
         * Cross-encoder logits and embedding cosine similarity are different score spaces. Once rerank produced a
         * score for the bounded stage-two window, do not append unreranked vector candidates and compare their raw
         * cosine values against those logits: that silently pushes reranked evidence out of the final Top K. The
         * complete vector-backed list remains available only when the rerank call itself yielded no usable scores.
         */
        boolean rerankAvailable = !semanticScoreByKey.isEmpty();
        List<BlockCandidate> blockCandidates = new ArrayList<>();
        for (DocumentCandidate candidate : rankedDocuments) {
            // Candidate blocks already belong to one file-level parent.  Do not look them up by documentId here:
            // a Feishu root can own many files under that same documentId.
            List<BlockContext> documentBlocks = candidate.blocks();
            for (BlockContext block : candidate.blocks()) {
                String key = blockKey(candidate.document().documentId(), block.block().blockId());
                if (rerankAvailable && !semanticScoreByKey.containsKey(key)) {
                    continue;
                }
                double semantic = semanticScoreByKey.getOrDefault(
                        key,
                        vectorScoreByKey.getOrDefault(key, 0.0d));
                int lexicalMatches = blockLexicalMatchCount(candidate.document(), block, normalizedQuery, terms);
                blockCandidates.add(new BlockCandidate(
                        candidate.document(),
                        block,
                        documentBlocks,
                        semantic,
                        lexicalMatches,
                        candidate.coarseScore(),
                        vectorScoreByKey.getOrDefault(key, 0.0d),
                        roleIntentScore(roleIntentQuery, block)));
            }
        }
        List<BlockCandidate> rankedCandidates = blockCandidates.stream()
                .filter(candidate -> filter.sourceTypes().isEmpty()
                        || candidate.lexicalMatches() > 0
                        || candidate.rerankScore() >= MINIMUM_SEMANTIC_SUPPORT_SCORE)
                .sorted(blockCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.block().block().blockOrder()))
                .toList();
        return rankedCandidates.stream()
                .limit(safeLimit)
                .map(candidate -> toTwoStageHit(candidate, candidate.parentBlocks(), normalizedQuery, terms))

                .toList();
    }

    /**
     * Stage-one document admission must stay wider than the final response size.
     *
     * <p>The final API limit answers "how many block hits should the caller see", not "how many documents are allowed
     * to compete for those hits". If we clamp candidate documents to the final top-N too early, one lexically generic
     * document can crowd out the actually correct document before semantic rerank ever sees it. The budget here is
     * therefore an I/O ceiling for candidate admission, while the final response size is enforced only after block
     * rerank.</p>
     */
    private int stageDocumentCandidateLimit(int visibleDocumentCount) {
        return searchBudget.documentRerankCandidateLimit(visibleDocumentCount);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static Comparator<DocumentCandidate> documentCandidateComparator() { return TeacherResourceBlockSearchPolicy.documentCandidateComparator(); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static Comparator<BlockContext> blockSupportComparator(TeacherResourceDocumentResponse document, Map<String, Double> vectorScoreByKey, String normalizedQuery, String[] terms) { return TeacherResourceBlockSearchPolicy.blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms); }
    /**
     * Stage two reranks bounded Milvus candidates through the dedicated GPU service. Any service failure propagates
     * instead of silently switching to request-time embedding cosine scores.
     */
    private Map<String, Double> semanticScoreByKey(
            List<DocumentCandidate> rankedDocuments,
            Map<String, List<BlockContext>> blocksByDocumentId,
            String normalizedQuery) {
        LinkedHashMap<String, String> candidateTexts = new LinkedHashMap<>();
        for (StageTwoBlockCandidate candidate : stageTwoCandidateBlocks(rankedDocuments, blocksByDocumentId)) {
            candidateTexts.put(
                    blockKey(candidate.document().documentId(), candidate.block().block().blockId()),
                    semanticCandidateText(
                            candidate.document(),
                            candidate.block(),
                            evidenceWindow(candidate.block(), candidate.documentBlocks()),
                            searchBudget.blockEvidenceChars()));
        }
        if (candidateTexts.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(candidateTexts.keySet());
        List<String> texts = keys.stream().map(candidateTexts::get).toList();
        List<Double> scores = vectorIndexService.rerankTexts(normalizedQuery, texts);
        if (scores.size() != keys.size()) {
            throw new IllegalStateException("Teacher resource rerank returned " + scores.size()
                    + " scores for " + keys.size() + " candidates");
        }
        Map<String, Double> scoreByKey = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index += 1) {
            scoreByKey.put(keys.get(index), scores.get(index));
        }
        return Map.copyOf(scoreByKey);
    }

    /**
     * Stage two keeps candidate selection fair across documents. Instead of "take exactly N blocks from every
     * document", we draw supported blocks round-robin from the ranked documents until the worker budget is full.
     * This preserves multi-document competition while still giving rich documents more than one chance.
     */
    private List<StageTwoBlockCandidate> stageTwoCandidateBlocks(
            List<DocumentCandidate> rankedDocuments,
            Map<String, List<BlockContext>> blocksByDocumentId) {
        List<StageTwoBlockCandidate> selected = new ArrayList<>();
        int candidateBudget = searchBudget.blockRerankCandidateLimit(rankedDocuments.size());
        Map<String, List<BlockContext>> orderedBlocksByDoc = new LinkedHashMap<>();
        Map<String, Integer> cursorByDoc = new LinkedHashMap<>();
        for (int candidateIndex = 0; candidateIndex < rankedDocuments.size(); candidateIndex += 1) {
            DocumentCandidate candidate = rankedDocuments.get(candidateIndex);
            String documentId = candidateKey(candidate, candidateIndex);
            List<BlockContext> documentBlocks = candidate.blocks();
            List<BlockContext> ordered = candidate.blocks().stream()
                    .filter(block -> documentBlocks.stream().anyMatch(existing -> existing.block().blockId().equals(block.block().blockId())))
                    .toList();
            if (!ordered.isEmpty()) {
                orderedBlocksByDoc.put(documentId, ordered);
                cursorByDoc.put(documentId, 0);
            }
        }
        while (selected.size() < candidateBudget) {
            boolean advanced = false;
            for (int candidateIndex = 0; candidateIndex < rankedDocuments.size(); candidateIndex += 1) {
                DocumentCandidate candidate = rankedDocuments.get(candidateIndex);
                String documentId = candidateKey(candidate, candidateIndex);
                List<BlockContext> ordered = orderedBlocksByDoc.get(documentId);
                if (ordered == null || ordered.isEmpty()) {
                    continue;
                }
                int cursor = cursorByDoc.getOrDefault(documentId, 0);
                if (cursor >= ordered.size()) {
                    continue;
                }
                List<BlockContext> documentBlocks = candidate.blocks();
                selected.add(new StageTwoBlockCandidate(candidate.document(), ordered.get(cursor), documentBlocks));
                cursorByDoc.put(documentId, cursor + 1);
                advanced = true;
                if (selected.size() >= candidateBudget) {
                    break;
                }
            }
            if (!advanced) {
                break;
            }
        }
        return List.copyOf(selected);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static Comparator<BlockCandidate> blockCandidateComparator() { return TeacherResourceBlockSearchPolicy.blockCandidateComparator(); }
    // Delegates explicit role and negation intent parsing to the stateless search policy.
    static int roleIntentScore(String query, BlockContext block) { return TeacherResourceBlockSearchPolicy.roleIntentScore(query, block); }

    private String semanticCandidateText(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            EvidenceWindow evidence,
            int evidenceCharBudget) {
        String imageRefs = truncateForRerank(
                String.join(" ", parseImageAssetIds(block.block().imageRefs())),
                searchBudget.imageRefsChars());
        String formulaEvidence = truncateForRerank(
                formulaEvidence(block.block().formulaRefs()),
                searchBudget.blockEvidenceChars());
        String evidenceText = textOrDefault(
                evidence == null ? "" : evidence.text(),
                textOrDefault(block.block().normalizedText(), block.block().rawText()));
        return String.join(
                "\n",
                "documentTitle: " + truncateForRerank(textOrDefault(document.title(), ""), searchBudget.titleChars()),
                "library: " + TeacherResourceLibraryResolver.effectiveLibrary(document),
                "role: " + truncateForRerank(textOrDefault(block.blockRole(), ""), searchBudget.roleChars()),
                "chapter: " + truncateForRerank(textOrDefault(block.block().chapter(), ""), searchBudget.headingChars()),
                "section: " + truncateForRerank(textOrDefault(block.block().section(), ""), searchBudget.headingChars()),
                "sourcePath: " + truncateForRerank(textOrDefault(block.sourcePath(), ""), searchBudget.sourcePathChars()),
                "graphTags: " + truncateForRerank(String.join(" ", block.graphTags()), searchBudget.graphTagsChars()),
                "imageRefs: " + imageRefs,
                "formulaEvidence: " + formulaEvidence,
                "evidenceBlockIds: " + truncateForRerank(
                        String.join(" ", evidence == null ? List.of() : evidence.blockIds()),
                        searchBudget.evidenceBlockIdsChars()),
                "evidenceText:\n" + truncateForRerank(evidenceText, evidenceCharBudget));
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static int blockLexicalMatchCount(TeacherResourceDocumentResponse document, BlockContext block, String normalizedQuery, String[] terms) { return TeacherResourceBlockSearchPolicy.blockLexicalMatchCount(document, block, normalizedQuery, terms); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static int lexicalMatchCount(String haystack, String normalizedQuery, String[] terms) { return TeacherResourceBlockSearchPolicy.lexicalMatchCount(haystack, normalizedQuery, terms); }

    private TeacherResourceBlockSearchResponse.Hit toTwoStageHit(
            BlockCandidate candidate,
            List<BlockContext> documentBlocks,
            String normalizedQuery,
            String[] terms) {
        BlockContext context = candidate.block();
        EvidenceWindow evidence = evidenceWindow(context, documentBlocks);
        return new TeacherResourceBlockSearchResponse.Hit(
                candidate.document().documentId(),
                candidate.document().title(),
                TeacherResourceLibraryResolver.effectiveLibrary(candidate.document()),
                candidate.document().permissionScope(),
                context.block().blockId(),
                context.block().blockType(),
                context.block().blockOrder(),
                context.block().chapter(),
                context.block().section(),
                context.block().pageNo(),
                fileName(context.sourcePath()),
                context.sourcePath(),
                context.blockRole(),
                context.graphTags(),
                evidence.blockIds(),
                evidence.text(),
                snippet(textOrDefault(context.block().rawText(), context.block().normalizedText()), normalizedQuery, terms),
                candidate.rerankScore(),

                parseImageAssetIds(context.block().imageRefs()),
                List.of());
    }

    private static String fileName(String sourcePath) {
        String normalized = textOrDefault(sourcePath, "").replace('\\', '/');
        if (normalized.isBlank()) return "";
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    /**
     * Returns the central hit block plus adjacent parsed neighbors. We do not expand arbitrarily: the goal is to keep
     * citations stable while still handling real evidence that spans a prompt block and the following answer/analysis.
     */
    private EvidenceWindow evidenceWindow(BlockContext target, List<BlockContext> documentBlocks) {
        if (documentBlocks == null || documentBlocks.isEmpty()) {
            return new EvidenceWindow(List.of(target.block().blockId()), blockEvidenceText(target.block()));
        }
        int targetIndex = -1;
        for (int index = 0; index < documentBlocks.size(); index += 1) {
            if (documentBlocks.get(index).block().blockId().equals(target.block().blockId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            return new EvidenceWindow(List.of(target.block().blockId()), blockEvidenceText(target.block()));
        }
        List<String> blockIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int start = Math.max(0, targetIndex - searchBudget.evidenceWindowRadius());
        int end = Math.min(documentBlocks.size() - 1, targetIndex + searchBudget.evidenceWindowRadius());
        for (int index = start; index <= end; index += 1) {
            BlockContext neighbor = documentBlocks.get(index);
            blockIds.add(neighbor.block().blockId());
            String text = blockEvidenceText(neighbor.block());
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return new EvidenceWindow(List.copyOf(blockIds), String.join("\n", texts));
    }

    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static BlockContext toContext(TeacherResourceDocumentResponse document, TeacherDocumentBlockResponse block) { return TeacherResourceBlockSearchPolicy.toContext(document, block); }

    /** Builds the document view used by ranking while retaining one entry per physical file parent. */
    private Map<String, TeacherResourceDocumentResponse> fileDocumentsByParent(
            Map<String, List<BlockContext>> blocksByParent) {
        Map<String, TeacherResourceDocumentResponse> documents = new LinkedHashMap<>();
        for (Map.Entry<String, List<BlockContext>> entry : blocksByParent.entrySet()) {
            entry.getValue().stream().findFirst().ifPresent(context -> documents.put(entry.getKey(), context.document()));
        }
        return documents;
    }

    /** Uses provider identity when present and isolates every legacy block with no source path. */
    private static String fileParentKey(String documentId, String providerItemId, String sourcePath, String blockId) {
        String identity = textOrDefault(providerItemId, "").strip();
        if (identity.isBlank()) identity = textOrDefault(sourcePath, "").replace('\\', '/').strip();
        if (identity.isBlank()) identity = "missing-source-path:" + textOrDefault(blockId, "");
        return textOrDefault(documentId, "") + "::" + identity;
    }

    private static String candidateKey(DocumentCandidate candidate, int index) {
        String path = candidate.blocks().stream().findFirst().map(BlockContext::sourcePath).orElse("");
        String block = candidate.blocks().stream().findFirst().map(context -> context.block().blockId()).orElse("");
        return fileParentKey(candidate.document().documentId(), path, path, block) + "::candidate:" + index;
    }

    /**
     * Builds a compact match snippet around the first exact or term match.
     */
    private String snippet(String rawText, String normalizedQuery, String[] terms) {
        String text = textOrDefault(rawText, "");
        if (text.isBlank()) {
            return "";
        }
        String lower = normalizeText(text);
        int matchIndex = lower.indexOf(normalizedQuery);
        if (matchIndex < 0) {
            matchIndex = Arrays.stream(terms)
                    .filter(term -> !term.isBlank())
                    .mapToInt(lower::indexOf)
                    .filter(index -> index >= 0)
                    .findFirst()
                    .orElse(0);
        }
        int start = Math.max(0, matchIndex - searchBudget.snippetRadius());
        int end = Math.min(text.length(), matchIndex + normalizedQuery.length() + searchBudget.snippetRadius());
        String prefix = start > 0 ? "..." : "";
        String suffix = end < text.length() ? "..." : "";
        return prefix + text.substring(start, end).strip() + suffix;
    }

    /**
     * Builds the response envelope.
     */
    private TeacherResourceBlockSearchResponse response(
            String normalizedQuery,
            int safeLimit,
            String retrievalMode,
            List<TeacherResourceBlockSearchResponse.Hit> hits) {
        return new TeacherResourceBlockSearchResponse(
                UUID.randomUUID().toString(),
                normalizedQuery,
                safeLimit,
                safeRetrievalMode(retrievalMode),
                hits.size(),
                hits);
    }

    /** Persists timing and hit metadata only; source正文仍不进入审计表。 */
    private void recordAudit(
            String tenantId,
            String subjectType,
            String subjectId,
            String endpoint,
            TeacherResourceBlockSearchResponse response,
            long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        auditSink.record(TeacherResourceBlockSearchAuditEvent.from(
                tenantId,
                subjectType,
                subjectId,
                textOrDefault(endpoint, "/api/teacher/resources/search"),
                response,
                elapsedMs));
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceBlockSearchResponse.Hit textbookHit(TextbookSearchHit hit) { return TeacherResourceBlockSearchPolicy.textbookHit(hit); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceBlockSearchResponse.Hit textbookImageHit(TextbookPageImageSearchHit hit) { return TeacherResourceBlockSearchPolicy.textbookImageHit(hit); }

    /**
     * Cross-source merging now uses the same real rerank primitive as the main two-stage pipeline instead of reciprocal
     * rank math. That keeps teacher blocks, textbook chunks, and textbook page-image hits in one semantic ranking
     * space, while lexical overlap stays only as a tie-breaker.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> semanticMergeHits(
            String semanticQuery,
            String[] terms,
            List<TeacherResourceBlockSearchResponse.Hit> hits,
            int safeLimit) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, TeacherResourceBlockSearchResponse.Hit> deduplicated = new LinkedHashMap<>();
        for (TeacherResourceBlockSearchResponse.Hit hit : hits) {
            if (hit == null) {
                continue;
            }
            String key = blockKey(hit.documentId(), hit.blockId());
            deduplicated.merge(key, hit, TeacherResourceBlockSearchService::preferMergeHit);
        }
        List<TeacherResourceBlockSearchResponse.Hit> candidates = List.copyOf(deduplicated.values());
        List<String> candidateTexts = candidates.stream()
                .map(this::semanticMergeCandidateText)
                .toList();
        List<Double> rerankScores;
        try {
            rerankScores = vectorIndexService.rerankTexts(semanticQuery, candidateTexts);
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_merge_rerank_fallback query={} message={}",
                    semanticQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            rerankScores = List.of();
        }
        List<MergeCandidate> mergeCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index += 1) {
            TeacherResourceBlockSearchResponse.Hit hit = candidates.get(index);
            mergeCandidates.add(new MergeCandidate(
                    hit,
                    rerankScores.size() > index ? rerankScores.get(index) : hit.score(),
                    lexicalMatchCount(candidateTexts.get(index), semanticQuery, terms)));
        }
        return mergeCandidates.stream()
                .sorted(Comparator.comparingDouble(MergeCandidate::rerankScore).reversed()
                        .thenComparing(Comparator.comparingDouble(MergeCandidate::sourceScore).reversed())
                        .thenComparing(Comparator.comparingInt(MergeCandidate::lexicalMatches).reversed())
                        .thenComparing(candidate -> candidate.hit().documentTitle())
                        .thenComparing(candidate -> candidate.hit().documentId())
                        .thenComparing(candidate -> candidate.hit().blockId()))
                .limit(safeLimit)
                .map(candidate -> withScore(candidate.hit(), candidate.rerankScore()))
                .toList();
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceBlockSearchResponse.Hit preferMergeHit(TeacherResourceBlockSearchResponse.Hit left, TeacherResourceBlockSearchResponse.Hit right) { return TeacherResourceBlockSearchPolicy.preferMergeHit(left, right); }

    private String semanticMergeCandidateText(TeacherResourceBlockSearchResponse.Hit hit) {
        return String.join(
                "\n",
                "documentTitle: " + truncateForRerank(textOrDefault(hit.documentTitle(), ""), searchBudget.titleChars()),
                "sourceType: " + textOrDefault(hit.sourceType(), ""),
                "blockRole: " + truncateForRerank(textOrDefault(hit.blockRole(), ""), searchBudget.roleChars()),
                "chapter: " + truncateForRerank(textOrDefault(hit.chapter(), ""), searchBudget.headingChars()),
                "section: " + truncateForRerank(textOrDefault(hit.section(), ""), searchBudget.headingChars()),
                "sourcePath: " + truncateForRerank(textOrDefault(hit.sourcePath(), ""), searchBudget.sourcePathChars()),
                "graphTags: " + truncateForRerank(
                        String.join(" ", hit.graphTags() == null ? List.of() : hit.graphTags()),
                        searchBudget.graphTagsChars()),
                "evidenceText:\n" + truncateForRerank(
                        textOrDefault(hit.evidenceText(), hit.snippet()),
                        searchBudget.mergeEvidenceChars()));
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceBlockSearchResponse.Hit withScore(TeacherResourceBlockSearchResponse.Hit hit, double score) { return TeacherResourceBlockSearchPolicy.withScore(hit, score); }

    /**
     * Asset URLs are attached only after ranking so retrieval math stays content-driven while final hits still carry
     * safe image references for UI/AI rendering.
     */
    private TeacherResourceBlockSearchResponse attachVisibleAssetRefs(
            TeacherResourceBlockSearchResponse response,
            RequestSubject subject) {
        if (response == null || response.hits() == null || response.hits().isEmpty()) {
            return response;
        }
        List<TeacherResourceBlockSearchResponse.Hit> hits = response.hits().stream()
                .map(hit -> attachVisibleAssetRefs(hit, subject))
                .toList();
        return new TeacherResourceBlockSearchResponse(
                response.queryId(),
                response.query(),
                response.limit(),
                safeRetrievalMode(response.retrievalMode()),
                hits.size(),
                hits);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceBlockSearchResponse.Hit enrichNearbyImageAssets(TeacherResourceBlockSearchResponse.Hit hit, Map<String, List<TeacherDocumentBlockResponse>> blocksByDocument) { return TeacherResourceBlockSearchPolicy.enrichNearbyImageAssets(hit, blocksByDocument); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean samePage(Integer hitPage, Integer imagePage) { return TeacherResourceBlockSearchPolicy.samePage(hitPage, imagePage); }

    private TeacherResourceBlockSearchResponse.Hit attachVisibleAssetRefs(
            TeacherResourceBlockSearchResponse.Hit hit,
            RequestSubject subject) {
        if (hit == null || hit.imageAssetIds() == null || hit.imageAssetIds().isEmpty()) {
            return hit == null ? null : hit.withAssetRefs(List.of());
        }
        List<TeacherResourceBlockSearchResponse.AssetRef> assetRefs = hit.imageAssetIds().stream()
                .map(assetId -> assetService.findVisibleAssetReference(assetId, subject))
                .flatMap(Optional::stream)
                .map(TeacherResourceAssetService.VisibleAssetReference::toSearchAssetRef)
                .toList();
        return hit.withAssetRefs(assetRefs);
    }

    /**
     * Materializes one already permission-checked teacher image for the server-side LaTeX export.
     *
     * The search response intentionally exposes only an opaque API URI.  The handout renderer still needs a local
     * file, so this method opens the same authorized resource and returns its backend-owned persistent file when
     * available. Copying it to a temporary directory caused persisted teaching tasks to retain a path which vanished
     * before a later PDF export. The permission check still occurs on every call; no storage key reaches callers.
     */
    public Optional<Path> materializeVisibleAsset(String assetId, RequestSubject subject) {
        if (assetId == null || assetId.isBlank() || subject == null) {
            return Optional.empty();
        }
        try {
            TeacherResourceAssetService.VisibleAsset asset = assetService.openVisibleAsset(assetId.strip(), subject);
            try {
                Path persisted = asset.resource().getFile().toPath().toRealPath();
                if (Files.isRegularFile(persisted)) {
                    return Optional.of(persisted);
                }
            } catch (IOException ignored) {
                // Non-file resources (for example a future object-store adapter) use the safe backend staging copy
                // below. The result is valid for the current request but must not be persisted as task evidence.
            }
            Path directory = Files.createTempDirectory("math-agent-teacher-asset-");
            String fileName = Path.of(asset.fileName() == null ? "asset" : asset.fileName())
                    .getFileName()
                    .toString()
                    .replaceAll("[^A-Za-z0-9._-]", "_");
            if (fileName.isBlank()) {
                fileName = "asset";
            }
            Path target = directory.resolve(fileName).normalize();
            if (!target.startsWith(directory)) {
                return Optional.empty();
            }
            try (InputStream input = asset.resource().getInputStream()) {
                Files.copy(input, target);
            }
            return Optional.of(target);
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("Unable to materialize authorized teacher asset {} for handout export", assetId, exception);
            return Optional.empty();
        }
    }

    /**
     * Finds the authorized page image for a question-bank child without weakening source visibility.
     *
     * <p>The importer stores atomic rows as {@code blockId#qN}; the page asset remains attached to {@code blockId}.
     * This resolver removes only that importer-owned child suffix, verifies the parent block in the same document,
     * then asks the asset service to apply tenant/owner/scope checks before it returns an opaque reference.</p>
     */
    public Optional<TeacherResourceBlockSearchResponse.AssetRef> resolveVisiblePageImageForQuestion(
            String documentId,
            String sourceBlockId,
            RequestSubject subject) {
        if (documentId == null || documentId.isBlank() || sourceBlockId == null || sourceBlockId.isBlank() || subject == null) {
            return Optional.empty();
        }
        RequestSubject normalized = subject.normalize();
        String parentBlockId = sourceBlockId.replaceFirst("#q\\d+$", "");
        return blockStore.listByDocument(normalized.tenantId(), documentId.strip()).stream()
                .filter(block -> parentBlockId.equals(block.blockId()))
                .map(TeacherDocumentBlockResponse::pageNo)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(pageNo -> assetService.findVisiblePageImageReference(documentId, pageNo, normalized))
                .map(TeacherResourceAssetService.VisibleAssetReference::toSearchAssetRef);
    }

    /**
     * Resolves a DOCX's original diagram that immediately follows the exact numbered source stem.
     *
     * <p>Page renderings are useful for inspection but are not a question figure: they can contain the preceding
     * answer, the next question, or no diagram at all.  This method therefore accepts only an image-only source
     * block located after the matching stem and before another numbered stem.  It deliberately returns empty when
     * that provenance cannot be established, allowing the handout gate to omit rather than mislabel a visual.</p>
     */
    public Optional<TeacherResourceBlockSearchResponse.AssetRef> resolveVisibleInlineFigureForQuestion(
            String documentId,
            String questionText,
            RequestSubject subject) {
        if (documentId == null || documentId.isBlank() || questionText == null || questionText.isBlank()
                || subject == null) {
            return Optional.empty();
        }
        Matcher requestedNumber = TOP_LEVEL_QUESTION_NUMBER.matcher(questionText);
        if (!requestedNumber.find()) {
            return Optional.empty();
        }
        String questionNumber = requestedNumber.group(1);
        RequestSubject normalized = subject.normalize();
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(normalized.tenantId(), documentId.strip())
                .stream()
                .sorted(Comparator.comparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
        for (int index = 0; index < blocks.size(); index += 1) {
            TeacherDocumentBlockResponse stem = blocks.get(index);
            // Rendered pages are intentionally excluded: their asset represents a page, not an extracted figure.
            if (stem.pageNo() != null || !matchesTopLevelQuestionNumber(stem.rawText(), questionNumber)) {
                continue;
            }
            int finalIndex = Math.min(blocks.size(), index + 1 + MAX_INLINE_FIGURE_LOOKAHEAD_BLOCKS);
            for (int candidateIndex = index + 1; candidateIndex < finalIndex; candidateIndex += 1) {
                TeacherDocumentBlockResponse candidate = blocks.get(candidateIndex);
                if (candidate.pageNo() != null || matchesAnyTopLevelQuestionNumber(candidate.rawText())) {
                    break;
                }

                List<String> assetIds = parseImageAssetIds(candidate.imageRefs());
                boolean imageOnlyBlock = !assetIds.isEmpty()
                        && textOrDefault(candidate.rawText(), "").contains("[DOCX image block; no extractable text]");
                if (!imageOnlyBlock) {
                    continue;
                }
                return assetIds.stream()
                        .map(assetId -> assetService.findVisibleAssetReference(assetId, normalized))
                        .flatMap(Optional::stream)
                        .map(TeacherResourceAssetService.VisibleAssetReference::toSearchAssetRef)
                        .findFirst();
            }
        }
        return Optional.empty();
    }

    /** Reads authoritative source files from the Docker volume; no document or block database lookup is performed. */
    public List<TeacherDocumentBlockResponse> listVisibleBlocks(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedDocumentId = textOrDefault(documentId, "");
        if (normalizedDocumentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (sourceFileReader == null) {
            throw new IllegalStateException("File-backed teacher source reader is not configured");
        }
        TeacherSourceFileReader.SourceDocument source = sourceFileReader.read(tenantId, normalizedDocumentId);
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        int order = 0;
        for (TeacherSourceFileReader.SourceFile file : source.files()) {
            String text = file.text();
            blocks.add(new TeacherDocumentBlockResponse(
                    "file:" + Integer.toUnsignedString((normalizedDocumentId + ":" + file.relativeName()).hashCode()),
                    normalizedDocumentId,
                    file.relativeName(),
                    "markdown",
                    order++,
                    "",
                    "",
                    null,
                    null,
                    text,
                    text,
                    "[]",
                    "[]",
                    "",
                    1.0d,
                    "active"));
        }
        return List.copyOf(blocks);
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean matchesTopLevelQuestionNumber(String text, String expectedNumber) { return TeacherResourceBlockSearchPolicy.matchesTopLevelQuestionNumber(text, expectedNumber); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean matchesAnyTopLevelQuestionNumber(String text) { return TeacherResourceBlockSearchPolicy.matchesAnyTopLevelQuestionNumber(text); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static void requireReaderRole(String viewerRole) { TeacherResourceBlockSearchPolicy.requireReaderRole(viewerRole); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<TeacherResourceDocumentResponse> filteredDocuments(List<TeacherResourceDocumentResponse> documents, TeacherResourceSearchFilter filter) { return TeacherResourceBlockSearchPolicy.filteredDocuments(documents, filter); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean isSyntheticOrBenchmarkSource(TeacherResourceDocumentResponse document) { return TeacherResourceBlockSearchPolicy.isSyntheticOrBenchmarkSource(document); }

    private boolean shouldUseRealTextbook(TeacherResourceSearchFilter filter) {
        if (!realTextbookAvailable()) {
            return false;
        }
        if (filter == null) {
            return false;
        }
        if (filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            // An unfiltered search is the mixed teacher-plus-textbook contract. The merge stage owns source
            // balancing, while the teacher-store filter below still removes stale public-textbook derivative rows.
            return true;
        }
        boolean textbookLibrary = filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
        /*
         * `library=textbook` changes documentId namespace from source_document to processed_books. This is required
         * for a teacher/agent that selected a concrete publisher edition; all other library combinations preserve the
         * teacher-document scope and never leak public textbook hits into a private document-id request.
         */
        return textbookLibrary && (filter.documentIds() == null || filter.documentIds().isEmpty()
                || filter.sourceTypes().stream()
                        .map(TeacherResourceBlockSearchService::normalizeText)
                        .allMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector)));
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean isTextbookOnlyFilter(TeacherResourceSearchFilter filter) { return TeacherResourceBlockSearchPolicy.isTextbookOnlyFilter(filter); }

    private boolean realTextbookAvailable() {
        return textbookRetrievalService != null && textbookResourceProperties != null;
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean matchesTags(TeacherResourceDocumentResponse document, TeacherDocumentBlockResponse block, List<String> tags) { return TeacherResourceBlockSearchPolicy.matchesTags(document, block, tags); }

    /**
     * Clamps query result size to keep the read path bounded.
     */
    private int clampLimit(int limit) {
        if (limit <= 0) {
            return searchProperties.defaultLimit();
        }
        return Math.min(limit, searchProperties.maxLimit());
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String normalizeQuery(String query) { return TeacherResourceBlockSearchPolicy.normalizeQuery(query); }

    /**
     * Teacher-facing queries often contain routing instructions such as library narrowing, answer-format constraints,
     * or "do not mix sources" reminders. Those clauses are useful for orchestration, but if we embed the entire
     * sentence directly they dilute the math intent and make stage-one document recall noisy.
     *
     * <p>This focus builder is intentionally generic rather than benchmark-coupled: it keeps the clauses that overlap
     * with normalized graph tags and falls back to the longest content clauses when graph alignment is sparse.</p>
     */
    private FocusedSearchQuery focusedQuery(
            String normalizedQuery,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (normalizedQuery.isBlank()) {
            return new FocusedSearchQuery(normalizedQuery, new String[0]);
        }
        List<String> clauses = queryClauses(normalizedQuery);
        List<String> primaryTags = normalizedQueryParts(queryGraph == null ? List.of() : queryGraph.primaryTagNames());
        List<String> expandedTags = normalizedQueryParts(queryGraph == null ? List.of() : queryGraph.expandedTagNames());
        LinkedHashSet<String> focusedParts = new LinkedHashSet<>();
        appendMatchingClauses(focusedParts, clauses, primaryTags);
        appendMatchingClauses(focusedParts, clauses, expandedTags);
        appendLongestRemainingClauses(focusedParts, clauses, queryFocusBudget.maxClauses());
        primaryTags.stream()
                .limit(queryFocusBudget.maxGraphTags())
                .forEach(tag -> addFocusedPart(focusedParts, tag));
        expandedTags.stream()
                .limit(queryFocusBudget.maxGraphTags())
                .forEach(tag -> addFocusedPart(focusedParts, tag));
        String semanticQuery = truncateForRerank(
                String.join(" ", focusedParts),
                queryFocusBudget.maxSemanticQueryChars());
        if (semanticQuery.isBlank()) {
            semanticQuery = normalizedQuery;
        }
        return new FocusedSearchQuery(semanticQuery, searchTerms(semanticQuery));
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<String> queryClauses(String normalizedQuery) { return TeacherResourceBlockSearchPolicy.queryClauses(normalizedQuery); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<String> normalizedQueryParts(List<String> values) { return TeacherResourceBlockSearchPolicy.normalizedQueryParts(values); }

    private void appendMatchingClauses(
            LinkedHashSet<String> focusedParts,
            List<String> clauses,
            List<String> normalizedTags) {
        if (focusedParts.size() >= queryFocusBudget.maxClauses() || normalizedTags.isEmpty()) {
            return;
        }
        for (String clause : clauses) {
            if (focusedParts.size() >= queryFocusBudget.maxClauses()) {
                return;
            }
            boolean matched = normalizedTags.stream().anyMatch(tag -> containsNormalized(clause, tag));
            if (matched) {
                addFocusedPart(focusedParts, clause);
            }
        }
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static void appendLongestRemainingClauses(LinkedHashSet<String> focusedParts, List<String> clauses, int maxClauses) { TeacherResourceBlockSearchPolicy.appendLongestRemainingClauses(focusedParts, clauses, maxClauses); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static void addFocusedPart(LinkedHashSet<String> focusedParts, String candidate) { TeacherResourceBlockSearchPolicy.addFocusedPart(focusedParts, candidate); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean containsNormalized(String haystack, String needle) { return TeacherResourceBlockSearchPolicy.containsNormalized(haystack, needle); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static TeacherResourceSearchFilter normalizeFilter(TeacherResourceSearchFilter filter, String normalizedQuery) { return TeacherResourceBlockSearchPolicy.normalizeFilter(filter, normalizedQuery); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<String> queryLibrarySelectors(String normalizedQuery) { return TeacherResourceBlockSearchPolicy.queryLibrarySelectors(normalizedQuery); }

    private String retrievalMode(String strategy, TeacherResourceSearchFilter filter, String suffix) {
        String mode = filter.empty() ? strategy : strategy + "_filtered";
        if (suffix == null || suffix.isBlank()) {
            return safeRetrievalMode(mode);
        }
        return safeRetrievalMode(mode + "_" + suffix);
    }

    /**
     * Audit rows persist retrieval mode in a compact varchar column. Truncation here is intentional: the response
     * label remains human-readable while search continues to work even if internal stage names evolve.
     */
    private String safeRetrievalMode(String retrievalMode) {
        String normalized = textOrDefault(retrievalMode, "").strip();
        if (normalized.length() <= searchBudget.maxRetrievalModeLength()) {
            return normalized;
        }
        return normalized.substring(0, searchBudget.maxRetrievalModeLength());
    }

    /**
     * Splits a normalized query into non-empty terms.
     */
    private String[] searchTerms(String normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return new String[0];
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String fragment : normalizedQuery.split("\\s+")) {
            appendSearchTerms(terms, fragment);
            if (terms.size() >= searchBudget.maxSearchTerms()) {
                break;
            }
        }
        return terms.stream()
                .limit(searchBudget.maxSearchTerms())
                .toArray(String[]::new);
    }

    /**
     * Extracts a compact set of lexical support terms from Chinese and Latin fragments. Real teacher queries are often
     * one continuous Chinese sentence with little punctuation, so whitespace tokenization alone collapses the entire
     * request into one unusable term and erases the lightweight lexical signal that should only assist semantic recall.
     */
    private void appendSearchTerms(LinkedHashSet<String> terms, String fragment) {
        String normalizedFragment = normalizeText(textOrDefault(fragment, ""));
        if (normalizedFragment.isBlank()) {
            return;
        }
        addSearchTerm(terms, normalizedFragment);
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int index = 0; index < normalizedFragment.length(); index += 1) {
            char current = normalizedFragment.charAt(index);
            if (isAsciiWordChar(current)) {
                if (cjk.length() > 0) {
                    appendCjkTerms(terms, cjk.toString());
                    cjk.setLength(0);
                }
                latin.append(current);
            } else if (isCjkChar(current)) {
                if (latin.length() > 0) {
                    addSearchTerm(terms, latin.toString());
                    latin.setLength(0);
                }
                cjk.append(current);
            } else {
                if (latin.length() > 0) {
                    addSearchTerm(terms, latin.toString());
                    latin.setLength(0);
                }
                if (cjk.length() > 0) {
                    appendCjkTerms(terms, cjk.toString());
                    cjk.setLength(0);
                }
            }
            if (terms.size() >= searchBudget.maxSearchTerms()) {
                return;
            }
        }
        if (latin.length() > 0) {
            addSearchTerm(terms, latin.toString());
        }
        if (cjk.length() > 0) {
            appendCjkTerms(terms, cjk.toString());
        }
    }

    private void appendCjkTerms(LinkedHashSet<String> terms, String fragment) {
        if (fragment.isBlank()) {
            return;
        }
        if (fragment.length() <= 6) {

            addSearchTerm(terms, fragment);
        }
        if (fragment.length() == 1) {
            return;
        }
        for (int index = 0; index < fragment.length() - 1 && terms.size() < searchBudget.maxSearchTerms(); index += 1) {
            addSearchTerm(terms, fragment.substring(index, index + 2));
        }
    }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static void addSearchTerm(LinkedHashSet<String> terms, String candidate) { TeacherResourceBlockSearchPolicy.addSearchTerm(terms, candidate); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String normalizeText(String text) { return TeacherResourceBlockSearchPolicy.normalizeText(text); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String truncateForRerank(String value, int maxChars) { return TeacherResourceBlockSearchPolicy.truncateForRerank(value, maxChars); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static void appendRerankLine(StringBuilder builder, String label, String value) { TeacherResourceBlockSearchPolicy.appendRerankLine(builder, label, value); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String textOrDefault(String value, String defaultValue) { return TeacherResourceBlockSearchPolicy.textOrDefault(value, defaultValue); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String requireText(String value, String message) { return TeacherResourceBlockSearchPolicy.requireText(value, message); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean containsAny(String haystack, String... needles) { return TeacherResourceBlockSearchPolicy.containsAny(haystack, needles); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean isAsciiWordChar(char value) { return TeacherResourceBlockSearchPolicy.isAsciiWordChar(value); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static boolean isCjkChar(char value) { return TeacherResourceBlockSearchPolicy.isCjkChar(value); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<String> parseStringArray(String json) { return TeacherResourceBlockSearchPolicy.parseStringArray(json); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static List<String> parseImageAssetIds(String json) { return TeacherResourceBlockSearchPolicy.parseImageAssetIds(json); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String formulaEvidence(String formulaRefs) { return TeacherResourceBlockSearchPolicy.formulaEvidence(formulaRefs); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String blockEvidenceText(TeacherDocumentBlockResponse block) { return TeacherResourceBlockSearchPolicy.blockEvidenceText(block); }
    // Delegates pure search normalization/ranking logic to TeacherResourceBlockSearchPolicy.
    static String blockKey(String documentId, String blockId) { return TeacherResourceBlockSearchPolicy.blockKey(documentId, blockId); }

    record BlockContext(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String searchableText,
            String sourcePath,
            String blockRole,
            List<String> graphTags,
            List<String> graphNodeIds) {
    }

    record DocumentCandidate(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            double coarseScore) {
    }

    record BlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            List<BlockContext> parentBlocks,
            double rerankScore,
            int lexicalMatches,
            double documentCoarseScore,
            double vectorSemanticScore,
            int roleIntentScore) {
    }

    record EvidenceWindow(
            List<String> blockIds,
            String text) {
    }

    record StageTwoBlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            List<BlockContext> documentBlocks) {
    }

    record MergeCandidate(
            TeacherResourceBlockSearchResponse.Hit hit,
            double rerankScore,
            int lexicalMatches) {
        double sourceScore() {
            return hit.score();
        }
    }

    record FocusedSearchQuery(
            String semanticQuery,
            String[] terms) {
    }

    record VectorCoarseRecall(
            List<VectorSearchHit> hits) {
        static final VectorCoarseRecall EMPTY = new VectorCoarseRecall(List.of());
    }

    /** Opaque reference that the authenticated teacher-resource detail endpoint can safely expand. */
    public record CanonicalReference(String documentId, String blockId, String documentTitle, String originalUrl) {
        public CanonicalReference(String documentId, String blockId, String documentTitle) {
            this(documentId, blockId, documentTitle, "");
        }
    }

    /** Internal ranked candidate; source and block scores remain separate to keep identity evidence auditable. */
    record CanonicalCandidate(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            int sourceScore,
            int blockScore) {
    }
}
