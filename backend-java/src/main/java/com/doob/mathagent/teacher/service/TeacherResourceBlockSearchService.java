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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(TeacherResourceBlockSearchService.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final String STRATEGY_TWO_STAGE_DOC_BLOCK = "two_stage_doc_block";
    private static final int MIN_TITLE_RECALL_TERM_LENGTH = 2;
    private static final Pattern VISUAL_EVIDENCE_QUERY_PATTERN = Pattern.compile(
            "(?:图|图片|如图|地图|image|figure)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_CLAUSE_SPLITTER = Pattern.compile("[\\r\\n,，。；;：:！？!?()（）\\[\\]【】]+");
    /**
     * Feishu document tokens survive title edits and distinguish two otherwise similarly named teaching handouts.
     * The resolver uses them only as one corroborating signal; a token alone never selects a block.
     */
    private static final Pattern STABLE_SOURCE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{11,})(?![A-Za-z0-9])");
    /** Numbered source stems are the only safe boundary for associating an adjacent DOCX diagram. */
    private static final Pattern TOP_LEVEL_QUESTION_NUMBER = Pattern.compile(
            "(?m)^\\h*(\\d{1,2})[.．、]\\h*");
    /** Keep the association local: an image beyond this many source blocks is not reliably question-owned. */
    private static final int MAX_INLINE_FIGURE_LOOKAHEAD_BLOCKS = 3;
    /**
     * A text paragraph and its Feishu image are emitted as separate blocks.  Retrieval ranks text, so bind only a
     * nearby image block from the same document instead of silently dropping the visual evidence from the hit.
     */
    private static final int MAX_NEARBY_IMAGE_BLOCK_DISTANCE = 4;
    /** A single evidence hit should not flood MCP/model context with every decorative image in a document. */
    private static final int MAX_IMAGE_ASSETS_PER_HIT = 2;

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

    /**
     * Converts a RAG hit from a historical synchronized mirror into an inspectable reference owned by the current
     * viewer.  Search and the resource-detail endpoint intentionally use different visibility contracts: searchable
     * shared mirrors may be returned by RAG, while the detail endpoint exposes only the viewer's current resource
     * library.  Returning the mirror id directly therefore produced a broken citation link.
     *
     * <p>The resolver never widens the detail endpoint.  It considers only {@link TeacherResourceStore#listVisible}
     * documents, then requires both a same-source signal (immutable source identity, source path, or stable Feishu
     * token) and a same-block signal (checksum, source path plus section, or exact normalized content).  An
     * ambiguous or incomplete match returns empty so callers can render evidence without advertising a false
     * “view original” link.</p>
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved subject id
     * @param hit original RAG hit, potentially from an old mirror
     * @return current visible document/block reference when one can be verified
     */
    public Optional<CanonicalReference> resolveVisibleReference(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceBlockSearchResponse.Hit hit) {
        if (hit == null || hit.documentId() == null || hit.documentId().isBlank()
                || hit.blockId() == null || hit.blockId().isBlank()) {
            return Optional.empty();
        }
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireReaderRole(normalizedRole);

        List<TeacherResourceDocumentResponse> visibleDocuments = resourceStore.listVisible(
                normalizedTenantId, normalizedRole, normalizedSubjectId);
        if (visibleDocuments.isEmpty()) {
            return Optional.empty();
        }
        Map<String, TeacherResourceDocumentResponse> visibleById = visibleDocuments.stream()
                .collect(Collectors.toMap(
                        TeacherResourceDocumentResponse::documentId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        Map<String, List<TeacherDocumentBlockResponse>> visibleBlocks = blockStore.listByDocuments(
                normalizedTenantId, List.copyOf(visibleById.keySet()));

        // The ordinary current-document path is also verified against active blocks.  This prevents a stale RAG
        // block id from being made clickable merely because a document row still happens to be visible.
        TeacherResourceDocumentResponse directlyVisible = visibleById.get(hit.documentId());
        if (directlyVisible != null) {
            Optional<TeacherDocumentBlockResponse> exactBlock = visibleBlocks
                    .getOrDefault(directlyVisible.documentId(), List.of()).stream()
                    .filter(block -> hit.blockId().equals(block.blockId()))
                    .findFirst();
            if (exactBlock.isPresent()) {
                return Optional.of(new CanonicalReference(
                        directlyVisible.documentId(), exactBlock.get().blockId(), directlyVisible.title()));
            }
        }

        TeacherResourceDocumentResponse mirrorDocument = resourceStore.find(normalizedTenantId, hit.documentId());
        TeacherDocumentBlockResponse mirrorBlock = mirrorDocument == null ? null : blockStore
                .listByDocument(normalizedTenantId, mirrorDocument.documentId()).stream()
                .filter(block -> hit.blockId().equals(block.blockId()))
                .findFirst()
                .orElse(null);
        String mirrorSourceIdentity = textOrDefault(mirrorDocument == null ? null : mirrorDocument.sourceIdentity(), "");
        String mirrorSourcePath = firstNonBlank(
                hit.sourcePath(), mirrorBlock == null ? null : mirrorBlock.sourcePath());
        String mirrorSection = firstNonBlank(hit.section(), mirrorBlock == null ? null : mirrorBlock.section());
        String mirrorChecksum = textOrDefault(mirrorBlock == null ? null : mirrorBlock.checksum(), "");
        String mirrorText = firstNonBlank(
                mirrorBlock == null ? null : mirrorBlock.normalizedText(), hit.evidenceText(), hit.snippet());
        Set<String> mirrorTokens = stableSourceTokens(
                hit.documentTitle(),
                mirrorDocument == null ? null : mirrorDocument.title(),
                mirrorDocument == null ? null : mirrorDocument.originalUrl(),
                mirrorSourceIdentity,
                mirrorSourcePath);

        List<CanonicalCandidate> candidates = new ArrayList<>();
        for (TeacherResourceDocumentResponse visibleDocument : visibleDocuments) {
            List<TeacherDocumentBlockResponse> candidateBlocks = visibleBlocks
                    .getOrDefault(visibleDocument.documentId(), List.of());
            int sourceScore = sourceAffinity(
                    mirrorSourceIdentity, mirrorSourcePath, mirrorTokens, visibleDocument, candidateBlocks);
            if (sourceScore == 0) {
                continue;
            }
            for (TeacherDocumentBlockResponse candidateBlock : candidateBlocks) {
                int blockScore = blockAffinity(
                        hit, mirrorSourcePath, mirrorSection, mirrorChecksum, mirrorText, candidateBlock);
                if (blockScore > 0) {
                    candidates.add(new CanonicalCandidate(visibleDocument, candidateBlock, sourceScore, blockScore));
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(CanonicalCandidate::sourceScore).reversed()
                        .thenComparing(Comparator.comparingInt(CanonicalCandidate::blockScore).reversed())
                        .thenComparing(candidate -> candidate.document().documentId())
                        .thenComparing(candidate -> candidate.block().blockId()))
                .findFirst()
                .map(candidate -> new CanonicalReference(
                        candidate.document().documentId(), candidate.block().blockId(), candidate.document().title()));
    }

    /** Scores only same-source metadata; it deliberately does not use a mutable display title as an identity. */
    private static int sourceAffinity(
            String mirrorSourceIdentity,
            String mirrorSourcePath,
            Set<String> mirrorTokens,
            TeacherResourceDocumentResponse candidateDocument,
            List<TeacherDocumentBlockResponse> candidateBlocks) {
        if (!mirrorSourceIdentity.isBlank()
                && mirrorSourceIdentity.equals(textOrDefault(candidateDocument.sourceIdentity(), ""))) {
            return 3;
        }
        boolean sameSourcePath = !mirrorSourcePath.isBlank() && candidateBlocks.stream()
                .anyMatch(block -> mirrorSourcePath.equals(textOrDefault(block.sourcePath(), "")));
        if (sameSourcePath) {
            return 2;
        }
        Set<String> candidateTokens = stableSourceTokens(
                candidateDocument.title(), candidateDocument.originalUrl(), candidateDocument.sourceIdentity());
        return mirrorTokens.stream().anyMatch(candidateTokens::contains) ? 1 : 0;
    }

    /**
     * Requires a strong same-block anchor after the source has been correlated.  A reused section title alone is
     * intentionally insufficient because many teacher collections have sections named “例题” or “方法”.
     */
    private static int blockAffinity(
            TeacherResourceBlockSearchResponse.Hit hit,
            String mirrorSourcePath,
            String mirrorSection,
            String mirrorChecksum,
            String mirrorText,
            TeacherDocumentBlockResponse candidateBlock) {
        boolean sameChecksum = !mirrorChecksum.isBlank()
                && mirrorChecksum.equals(textOrDefault(candidateBlock.checksum(), ""));
        boolean sameSourcePath = !mirrorSourcePath.isBlank()
                && mirrorSourcePath.equals(textOrDefault(candidateBlock.sourcePath(), ""));
        boolean sameSection = sameReferenceText(mirrorSection, candidateBlock.section());
        boolean sameText = sameReferenceText(mirrorText, candidateBlock.normalizedText())
                || sameReferenceText(mirrorText, candidateBlock.rawText());
        boolean sameExternalBlockId = mirrorBlockExternalIdMatches(hit, candidateBlock);
        if (!sameChecksum
                && !(sameSourcePath && sameSection)
                && !(sameSection && sameText)
                && !sameExternalBlockId) {
            return 0;
        }
        int score = 0;
        if (sameChecksum) {
            score += 16;
        }
        if (sameSourcePath) {
            score += 8;
        }
        if (sameSection) {
            score += 4;
        }
        if (sameText) {
            score += 3;
        }
        if (sameExternalBlockId) {
            score += 12;
        }
        return score;
    }

    private static boolean mirrorBlockExternalIdMatches(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeacherDocumentBlockResponse candidateBlock) {
        return hit.blockId().equals(candidateBlock.blockId())
                || hit.blockId().equals(textOrDefault(candidateBlock.externalBlockId(), ""));
    }

    /** Removes formatting-only differences before comparing synced Markdown blocks. */
    private static boolean sameReferenceText(String left, String right) {
        String normalizedLeft = normalizeText(textOrDefault(left, "")).replaceAll("[\\p{Punct}，。；：！？、】【（）\\s]+", "");
        String normalizedRight = normalizeText(textOrDefault(right, "")).replaceAll("[\\p{Punct}，。；：！？、】【（）\\s]+", "");
        return !normalizedLeft.isBlank() && normalizedLeft.equals(normalizedRight);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static Set<String> stableSourceTokens(String... values) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String value : values) {
            Matcher matcher = STABLE_SOURCE_TOKEN.matcher(textOrDefault(value, ""));
            while (matcher.find()) {
                tokens.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(tokens);
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
            List<TeacherResourceDocumentResponse> documents =
                    filteredDocuments(
                            resourceStore.listSearchable(normalizedTenantId, normalizedRole, normalizedSubjectId),
                            normalizedFilter);
            if (includeRealTextbook) {
                /*
                 * Once processed_books is available, teacher search must not keep a second stale textbook branch in
                 * source_document/document_block. Those imported PUBLIC_TEXTBOOK rows were only a historical bridge before
                 * the dedicated textbook retriever and page-image index existed. Keeping both sources in mixed mode lets
                 * old derivative rows compete against the real textbook page hit and makes "no library specified" behave
                 * differently from "library=textbook".
                 *
                 * We therefore delete teacher-store textbook derivatives whenever the real textbook retriever is allowed
                 * for this request, even when the caller is doing a mixed multi-library search.
                 */
                documents = documents.stream()
                        .filter(document -> !"public_textbook".equals(TeacherResourceLibraryResolver.effectiveLibrary(document)))
                        .toList();
            }
            searchResponse = twoStageResponse(
                    normalizedTenantId,
                    documents,
                    normalizedQuery,
                    focusedQuery,
                    safeLimit,
                    normalizedFilter,
                    queryGraph);
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
        searchResponse = attachVisibleAssetRefs(
                searchResponse,
                new RequestSubject(normalizedTenantId, normalizedRole, normalizedSubjectId, null));
        recordAudit(normalizedTenantId, normalizedRole, normalizedSubjectId, endpoint, searchResponse, startedNanos);
        return searchResponse;
    }

    private TeacherResourceBlockSearchResponse twoStageResponse(
            String tenantId,
            List<TeacherResourceDocumentResponse> documents,
            String normalizedQuery,
            FocusedSearchQuery focusedQuery,
            int safeLimit,
            TeacherResourceSearchFilter filter,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        if (documents.isEmpty()) {
            return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "no_visible_documents"), List.of());
        }
        Map<String, TeacherResourceDocumentResponse> documentsById = documents.stream()
                .collect(Collectors.toMap(
                        TeacherResourceDocumentResponse::documentId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        List<String> visibleDocumentIds = List.copyOf(documentsById.keySet());
        VectorCoarseRecall vectorCoarseRecall = vectorCoarseRecall(
                focusedQuery.semanticQuery(),
                safeLimit,
                visibleDocumentIds,
                filter);
        // A precise teacher document title is authoritative evidence even when a noisy global vector top-N admits
        // unrelated pages first.  Add title matches to the bounded candidate set before block reranking so a newly
        // synchronized Feishu document such as “涂色问题” cannot disappear behind older image-heavy resources.
        LinkedHashSet<String> titleCandidateIds = VISUAL_EVIDENCE_QUERY_PATTERN.matcher(normalizedQuery).find()
                ? documents.stream()
                        .filter(document -> titleMatchesQuery(document.title(), normalizedQuery, focusedQuery.terms()))
                        .map(TeacherResourceDocumentResponse::documentId)
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                : new LinkedHashSet<>();
        if (!titleCandidateIds.isEmpty()) {
            LinkedHashSet<String> mergedCandidateIds = new LinkedHashSet<>(vectorCoarseRecall.candidateDocumentIds());
            mergedCandidateIds.addAll(titleCandidateIds);
            vectorCoarseRecall = new VectorCoarseRecall(
                    vectorCoarseRecall.scoreByKey(),
                    List.copyOf(mergedCandidateIds));
        }
        Map<String, List<BlockContext>> blocksByDocumentId = stageOneBlockContexts(
                tenantId,
                documentsById,
                visibleDocumentIds,
                vectorCoarseRecall.candidateDocumentIds(),
                filter.tags());
        if (blocksByDocumentId.isEmpty()) {
            return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "no_visible_blocks"), List.of());
        }
        Map<String, Double> vectorScoreByKey = vectorCoarseRecall.scoreByKey();
        List<DocumentCandidate> documentCandidates = rerankedDocumentCandidates(
                documentsById,
                blocksByDocumentId,
                vectorScoreByKey,
                focusedQuery.semanticQuery(),
                focusedQuery.terms(),
                safeLimit,
                queryGraph);
        if (documentCandidates.isEmpty() && blocksByDocumentId.size() < visibleDocumentIds.size()) {
            /*
             * Semantic coarse recall is the primary path, but it must not become a hard gate. If the initial vector
             * admission window missed every surviving document after block/tag filtering, fall back once to the full
             * visible corpus so lexical rescue can still admit a document and the real reranker can judge it.
             */
            blocksByDocumentId = loadVisibleBlockContexts(tenantId, documentsById, visibleDocumentIds, filter.tags());
            if (blocksByDocumentId.isEmpty()) {
                return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, "no_visible_blocks"), List.of());
            }
            documentCandidates = rerankedDocumentCandidates(
                    documentsById,
                    blocksByDocumentId,
                    vectorScoreByKey,
                    focusedQuery.semanticQuery(),
                    focusedQuery.terms(),
                    safeLimit,
                    queryGraph);
        }
        List<DocumentCandidate> rankedDocuments = documentCandidates.stream()
                .sorted(documentCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.document().documentId()))
                .limit(stageDocumentCandidateLimit(documentCandidates.size()))
                .toList();
        List<TeacherResourceBlockSearchResponse.Hit> hits = rerankedBlockHits(
                rankedDocuments,
                blocksByDocumentId,
                vectorScoreByKey,
                focusedQuery.semanticQuery(),
                focusedQuery.terms(),
                safeLimit,
                filter,
                queryGraph);
        return response(normalizedQuery, safeLimit, retrievalMode(STRATEGY_TWO_STAGE_DOC_BLOCK, filter, null), hits);
    }

    /** Matches meaningful focused terms against a real source title for deterministic lexical admission. */
    private static boolean titleMatchesQuery(String title, String normalizedQuery, String[] focusedTerms) {
        String normalizedTitle = normalizeQuery(title);
        if (normalizedTitle.isBlank()) {
            return false;
        }
        if (!normalizedQuery.isBlank() && normalizedTitle.contains(normalizedQuery)) {
            return true;
        }
        if (focusedTerms == null) {
            return false;
        }
        for (String term : focusedTerms) {
            String normalizedTerm = normalizeQuery(term);
            if (normalizedTerm.length() >= MIN_TITLE_RECALL_TERM_LENGTH && normalizedTitle.contains(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

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
        if (visibleDocumentIds != null
                && visibleDocumentIds.size() <= stageDocumentCandidateLimit(visibleDocumentIds.size())) {
            return loadVisibleBlockContexts(tenantId, documentsById, visibleDocumentIds, tags);
        }
        if (semanticCandidateDocumentIds == null || semanticCandidateDocumentIds.isEmpty()) {
            return loadVisibleBlockContexts(tenantId, documentsById, visibleDocumentIds, tags);
        }
        LinkedHashSet<String> candidateDocumentIds = semanticCandidateDocumentIds.stream()
                .filter(documentsById::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (candidateDocumentIds.isEmpty()) {
            return loadVisibleBlockContexts(tenantId, documentsById, visibleDocumentIds, tags);
        }
        return loadVisibleBlockContexts(tenantId, documentsById, List.copyOf(candidateDocumentIds), tags);
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

    private static boolean isExplicitMixedLibraryFilter(TeacherResourceSearchFilter filter) {
        if (filter == null || filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            return false;
        }
        boolean includesTextbook = filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
        return includesTextbook && filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .anyMatch(selector -> !"textbook".equals(selector) && !"public_textbook".equals(selector));
    }

    private static List<TeacherResourceBlockSearchResponse.Hit> applyCrossSourceQuota(
            List<TeacherResourceBlockSearchResponse.Hit> rankedHits,
            int limit) {
        int boundedLimit = Math.max(1, limit);
        int teacherQuota = (boundedLimit + 1) / 2;
        int textbookQuota = boundedLimit - teacherQuota;
        List<TeacherResourceBlockSearchResponse.Hit> selected = new ArrayList<>(boundedLimit);
        int teacherCount = 0;
        int textbookCount = 0;
        for (TeacherResourceBlockSearchResponse.Hit hit : rankedHits) {
            boolean textbook = "public_textbook".equals(normalizeText(hit.sourceType()));
            if ((textbook && textbookCount >= textbookQuota) || (!textbook && teacherCount >= teacherQuota)) {
                continue;
            }
            selected.add(hit);
            if (textbook) {
                textbookCount++;
            } else {
                teacherCount++;
            }
        }
        // A source with fewer candidates must not leave result slots empty; retain global rerank order for the remainder.
        for (TeacherResourceBlockSearchResponse.Hit hit : rankedHits) {
            if (selected.size() >= boundedLimit) {
                break;
            }
            if (!selected.contains(hit)) {
                selected.add(hit);
            }
        }
        return List.copyOf(selected);
    }

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

    private static String buildTextbookMergeMode(
            TeacherResourceBlockSearchResponse teacherResponse,
            TextbookSearchResponse textbookResponse,
            boolean usedImageRoute) {
        /*
         * This value is persisted into teacher_resource_search_audit.retrieval_mode (VARCHAR(64)).
         * Keep it stable and short: the mode is only a diagnostic label, not a place to serialize
         * every internal stage name. If we concatenate upstream mode names here, live textbook
         * queries can start failing with audit insert errors even though retrieval itself succeeded.
         */
        StringBuilder mode = new StringBuilder("two_stage_doc_block_textbook");
        if (usedImageRoute) {
            mode.append("_clip");
        }
        if (textbookResponse != null
                && textbookResponse.retrievalStrategy() != null
                && textbookResponse.retrievalStrategy().contains("page")) {
            mode.append("_page");
        }
        mode.append("_semantic");
        return mode.toString();
    }

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
            List<BlockContext> blocks = blocksByDocumentId.getOrDefault(documentId, List.of()).stream()
                    .filter(block -> matchesTags(document, block, tags))
                    .map(block -> toContext(document, block))
                    .toList();
            if (!blocks.isEmpty()) {
                contexts.put(documentId, blocks);
            }
        }
        return contexts;
    }

    /**
     * Stage one groups vector hits by document instead of consuming them directly as final block hits. This is the
     * main behavioral change: vector search helps decide which document is plausible, then stage two resolves the
     * correct block inside that document.
     */
    private VectorCoarseRecall vectorCoarseRecall(
            String normalizedQuery,
            int safeLimit,
            List<String> visibleDocumentIds,
            TeacherResourceSearchFilter filter) {
        if (visibleDocumentIds == null || visibleDocumentIds.isEmpty()) {
            return VectorCoarseRecall.EMPTY;
        }
        int vectorCandidateLimit = searchBudget.vectorCandidateLimit(
                safeLimit,
                stageDocumentCandidateLimit(visibleDocumentIds.size()));
        Map<String, Double> scores = new LinkedHashMap<>();
        LinkedHashSet<String> candidateDocumentIds = new LinkedHashSet<>();
        List<VectorSearchHit> hits;
        try {
            hits = vectorIndexService.searchTeacherResourceBlocks(
                    normalizedQuery,
                    vectorCandidateLimit,
                    new VectorSearchFilter(List.copyOf(visibleDocumentIds), filter.permissionScopes()));
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_vector_fallback strategy=two_stage query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return VectorCoarseRecall.EMPTY;
        }
        Set<String> visibleDocumentIdSet = new LinkedHashSet<>(visibleDocumentIds);
        for (VectorSearchHit hit : hits) {
            if (!visibleDocumentIdSet.contains(hit.documentId())) {
                continue;
            }
            candidateDocumentIds.add(hit.documentId());
            String key = blockKey(hit.documentId(), hit.blockId());
            scores.merge(key, hit.score(), Math::max);
        }
        return new VectorCoarseRecall(Map.copyOf(scores), List.copyOf(candidateDocumentIds));
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
            return blocks.stream()
                    .filter(block -> vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d) > 0.0d)
                    .sorted(blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms))
                    .toList();
        }
        Map<String, Double> semanticFallbackScores = semanticFallbackScoreByBlockKey(document, blocks, normalizedQuery);
        if (!semanticFallbackScores.isEmpty()) {
            return blocks.stream()
                    .sorted(semanticFallbackBlockComparator(document, semanticFallbackScores, normalizedQuery, terms))
                    .limit(searchBudget.maxBlocksPerDocumentForStageTwo())
                    .toList();
        }
        /*
         * Lexical rescue is now the last fallback only. When neither Milvus block search nor the embedding similarity
         * fallback can provide support scores, we still allow a token-overlap path so completely degraded environments
         * do not collapse to zero recall.
         */
        return blocks.stream()
                .filter(block -> blockLexicalMatchCount(document, block, normalizedQuery, terms) > 0)
                .sorted(blockSupportComparator(document, vectorScoreByKey, normalizedQuery, terms))
                .limit(searchBudget.maxBlocksPerDocumentForStageTwo())
                .toList();
    }

    /**
     * If stage-one Milvus recall misses every block in a visible document, fall back once to embedding-space
     * similarity over that document's own blocks instead of immediately gating the document by lexical overlap. This
     * keeps the coarse stage semantic-first while still local to one document, avoiding a full-corpus rescoring pass.
     */
    private Map<String, Double> semanticFallbackScoreByBlockKey(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            String normalizedQuery) {
        if (blocks == null || blocks.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> candidateTexts = new LinkedHashMap<>();
        for (BlockContext block : blocks) {
            String key = blockKey(document.documentId(), block.block().blockId());
            candidateTexts.put(
                    key,
                    semanticCandidateText(
                            document,
                            block,
                            evidenceWindow(block, blocks),
                            searchBudget.blockEvidenceChars()));
        }
        try {
            List<String> keys = new ArrayList<>(candidateTexts.keySet());
            List<Double> scores = vectorIndexService.semanticSimilarity(
                    normalizedQuery,
                    keys.stream().map(candidateTexts::get).toList());
            if (scores.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> scoreByKey = new LinkedHashMap<>();
            for (int index = 0; index < keys.size() && index < scores.size(); index += 1) {
                scoreByKey.put(keys.get(index), scores.get(index));
            }
            boolean hasPositiveScore = scoreByKey.values().stream().anyMatch(score -> score > 0.0d);
            return hasPositiveScore ? Map.copyOf(scoreByKey) : Map.of();
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_semantic_block_rescue_fallback query={} documentId={} message={}",
                    normalizedQuery,
                    document.documentId(),
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return Map.of();
        }
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
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(candidate.document().documentId(), List.of());
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
                        semantic,
                        lexicalMatches,
                        candidate.coarseScore(),
                        vectorScoreByKey.getOrDefault(key, 0.0d)));
            }
        }
        List<BlockCandidate> rankedCandidates = blockCandidates.stream()
                .sorted(blockCandidateComparator()
                        .thenComparing(candidate -> candidate.document().title())
                        .thenComparing(candidate -> candidate.block().block().blockOrder()))
                .toList();
        return rankedCandidates.stream()
                .limit(safeLimit)
                .map(candidate -> toTwoStageHit(candidate, blocksByDocumentId.get(candidate.document().documentId()), normalizedQuery, terms))
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

    /**
     * Stage-one document order is the Milvus semantic coarse-recall order. Lexical, graph, and role data only admit
     * fallback candidates or enrich the later rerank payload; they do not create an opaque weighted score cocktail.
     */
    private static Comparator<DocumentCandidate> documentCandidateComparator() {
        return Comparator.comparingDouble(DocumentCandidate::coarseScore).reversed();
    }

    private static Comparator<BlockContext> blockSupportComparator(
            TeacherResourceDocumentResponse document,
            Map<String, Double> vectorScoreByKey,
            String normalizedQuery,
            String[] terms) {
        return Comparator.<BlockContext>comparingDouble(
                        block -> vectorScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms)).reversed())
                .thenComparing(block -> block.block().blockOrder());
    }

    private static Comparator<BlockContext> semanticFallbackBlockComparator(
            TeacherResourceDocumentResponse document,
            Map<String, Double> semanticScoreByKey,
            String normalizedQuery,
            String[] terms) {
        return Comparator.<BlockContext>comparingDouble(
                        block -> semanticScoreByKey.getOrDefault(blockKey(document.documentId(), block.block().blockId()), 0.0d))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (BlockContext block) -> blockLexicalMatchCount(document, block, normalizedQuery, terms)).reversed())
                .thenComparing(block -> block.block().blockOrder());
    }

    /**
     * Stage two reranks candidate blocks with a dedicated rerank model when the worker provides one, then falls back
     * to embedding cosine similarity inside {@link VectorIndexService}. The candidate text carries title/chapter/
     * section/role context so the rerank can decide between near-duplicate sibling blocks inside the same document.
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
        try {
            List<Double> scores = vectorIndexService.rerankTexts(normalizedQuery, texts);
            Map<String, Double> scoreByKey = new LinkedHashMap<>();
            for (int index = 0; index < keys.size() && index < scores.size(); index += 1) {
                scoreByKey.put(keys.get(index), scores.get(index));
            }
            return Map.copyOf(scoreByKey);
        } catch (RuntimeException exception) {
            log.warn("teacher_resource_search_block_rerank_fallback query={} message={}",
                    normalizedQuery,
                    textOrDefault(exception.getMessage(), ""),
                    exception);
            return Map.of();
        }
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
        for (DocumentCandidate candidate : rankedDocuments) {
            String documentId = candidate.document().documentId();
            List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(documentId, candidate.blocks());
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
            for (DocumentCandidate candidate : rankedDocuments) {
                String documentId = candidate.document().documentId();
                List<BlockContext> ordered = orderedBlocksByDoc.get(documentId);
                if (ordered == null || ordered.isEmpty()) {
                    continue;
                }
                int cursor = cursorByDoc.getOrDefault(documentId, 0);
                if (cursor >= ordered.size()) {
                    continue;
                }
                List<BlockContext> documentBlocks = blocksByDocumentId.getOrDefault(documentId, ordered);
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

    /**
     * Legacy role-bucket heuristics were intentionally removed here. The previous implementation tried to infer
     * "analysis/question/lesson" intent from hand-written cue lists and then override the semantic ranking. That made
     * retrieval behavior brittle and benchmark-sensitive. The rewritten pipeline keeps blockRole/sourcePath/chapter/
     * section and the adjacent evidence window inside the rerank text itself, so the real rerank model stays primary
     * while lexical and graph signals only break ties.
     */
    private static Comparator<BlockCandidate> blockCandidateComparator() {
        Comparator<BlockCandidate> comparator = Comparator.comparingDouble(BlockCandidate::rerankScore).reversed();
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::documentCoarseScore).reversed());
        comparator = comparator.thenComparing(Comparator.comparingDouble(BlockCandidate::vectorSemanticScore).reversed());
        return comparator.thenComparing(Comparator.comparingInt(BlockCandidate::lexicalMatches).reversed());
    }

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

    private static int blockLexicalMatchCount(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            String normalizedQuery,
            String[] terms) {
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                textOrDefault(block.block().chapter(), ""),
                textOrDefault(block.block().section(), ""),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), ""),
                textOrDefault(block.block().normalizedText(), block.block().rawText())));
        return lexicalMatchCount(haystack, normalizedQuery, terms);
    }

    private static int lexicalMatchCount(String haystack, String normalizedQuery, String[] terms) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        if (normalizedHaystack.isBlank()) {
            return 0;
        }
        int matches = normalizedHaystack.contains(normalizedQuery) ? 1 : 0;
        for (String term : terms) {
            if (!term.isBlank() && normalizedHaystack.contains(term)) {
                matches += 1;
            }
        }
        return matches;
    }

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

    /**
     * Records the search audit event without letting audit failures break read-only search.
     */
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

    private static BlockContext toContext(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block) {
        return new BlockContext(
                document,
                block,
                normalizeText(textOrDefault(block.normalizedText(), block.rawText())),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), "reference"),
                parseStringArray(block.graphTagNamesJson()),
                parseStringArray(block.graphNodeIdsJson()));
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

    /**
     * Translates one textbook page hit into the teacher-search hit contract so callers can keep one response parser.
     * The synthetic sourcePath is stable and clearly marks that this evidence came from processed_books rather than a
     * teacher-uploaded document row.
     */
    private static TeacherResourceBlockSearchResponse.Hit textbookHit(TextbookSearchHit hit) {
        String section = textOrDefault(hit.sectionTitle(), "");
        String chapter = hit.chapterPath() == null || hit.chapterPath().isEmpty()
                ? ""
                : String.join(" / ", hit.chapterPath());
        String evidenceText = textOrDefault(hit.textSnippet(), "");
        if (hit.formulaText() != null && !hit.formulaText().isBlank()) {
            evidenceText = evidenceText.isBlank() ? hit.formulaText() : evidenceText + "\n" + hit.formulaText();
        }
        String sourcePath = "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId();
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.docId(),
                hit.bookName(),
                "public_textbook",
                "PUBLIC_TEXTBOOK",
                hit.chunkId(),
                "page_chunk",
                Math.max(hit.pageNo(), 0),
                chapter,
                section,
                hit.pageNo(),
                sourcePath,
                "reference",
                List.of(),
                List.of(hit.chunkId()),
                evidenceText,
                textOrDefault(hit.textSnippet(), evidenceText),
                hit.score(),
                List.of(),
                List.of());
    }

    /**
     * Textbook page-image retrieval returns page-level evidence from the same processed_books corpus, but through the
     * CLIP index maintained by the Python worker. We map it into the same teacher-search hit contract so it can join
     * the final semantic merge with text chunks.
     */
    private static TeacherResourceBlockSearchResponse.Hit textbookImageHit(TextbookPageImageSearchHit hit) {
        String blockId = hit.docId() + "_p" + hit.pageNo() + "_clip";
        String sourcePath = "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#clip=page";
        String evidenceText = textOrDefault(hit.text(), "");
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.docId(),
                hit.bookName(),
                "public_textbook",
                "PUBLIC_TEXTBOOK",
                blockId,
                "page_image",
                Math.max(hit.pageNo(), 0),
                textOrDefault(hit.chapterPath(), ""),
                textOrDefault(hit.sectionTitle(), ""),
                hit.pageNo(),
                sourcePath,
                "reference",
                List.of(),
                List.of(blockId),
                evidenceText,
                evidenceText,
                hit.score(),
                List.of(),
                List.of());
    }

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

    private static TeacherResourceBlockSearchResponse.Hit preferMergeHit(
            TeacherResourceBlockSearchResponse.Hit left,
            TeacherResourceBlockSearchResponse.Hit right) {
        int leftEvidenceLength = textOrDefault(left.evidenceText(), left.snippet()).length();
        int rightEvidenceLength = textOrDefault(right.evidenceText(), right.snippet()).length();
        if (rightEvidenceLength > leftEvidenceLength) {
            return right.score() >= left.score() ? right : withScore(right, left.score());
        }
        return left.score() >= right.score() ? left : withScore(left, right.score());
    }

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

    private static TeacherResourceBlockSearchResponse.Hit withScore(
            TeacherResourceBlockSearchResponse.Hit hit,
            double score) {
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.documentId(),
                hit.documentTitle(),
                hit.sourceType(),
                hit.permissionScope(),
                hit.blockId(),
                hit.blockType(),
                hit.blockOrder(),
                hit.chapter(),
                hit.section(),
                hit.pageNo(),
                hit.sourcePath(),
                hit.blockRole(),
                hit.graphTags(),
                hit.evidenceBlockIds(),
                hit.evidenceText(),
                hit.snippet(),
                score,
                hit.imageAssetIds(),
                hit.assetRefs());
    }

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
        Map<String, List<TeacherDocumentBlockResponse>> blocksByDocument = response.hits().stream()
                .filter(Objects::nonNull)
                .map(TeacherResourceBlockSearchResponse.Hit::documentId)
                .filter(documentId -> documentId != null && !documentId.isBlank())
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        documentId -> blockStore.listByDocument(subject.tenantId(), documentId),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        List<TeacherResourceBlockSearchResponse.Hit> hits = response.hits().stream()
                .map(hit -> attachVisibleAssetRefs(enrichNearbyImageAssets(hit, blocksByDocument), subject))
                .toList();
        return new TeacherResourceBlockSearchResponse(
                response.queryId(),
                response.query(),
                response.limit(),
                safeRetrievalMode(response.retrievalMode()),
                hits.size(),
                hits);
    }

    /**
     * Adds image ids from the nearest parsed sibling block when the ranked text block itself has no image.
     * Same-page images win for paged sources; block distance is the deterministic fallback for Feishu/DOCX content.
     */
    private static TeacherResourceBlockSearchResponse.Hit enrichNearbyImageAssets(
            TeacherResourceBlockSearchResponse.Hit hit,
            Map<String, List<TeacherDocumentBlockResponse>> blocksByDocument) {
        if (hit == null || hit.imageAssetIds() != null && !hit.imageAssetIds().isEmpty()) {
            return hit;
        }
        List<TeacherDocumentBlockResponse> blocks = blocksByDocument.getOrDefault(hit.documentId(), List.of());
        List<String> nearbyAssetIds = blocks.stream()
                .filter(block -> "active".equalsIgnoreCase(block.status()))
                .filter(block -> Math.abs(block.blockOrder() - hit.blockOrder()) <= MAX_NEARBY_IMAGE_BLOCK_DISTANCE)
                .filter(block -> !parseImageAssetIds(block.imageRefs()).isEmpty())
                .sorted(Comparator
                        .comparing((TeacherDocumentBlockResponse block) -> !samePage(hit.pageNo(), block.pageNo()))
                        .thenComparingInt(block -> Math.abs(block.blockOrder() - hit.blockOrder()))
                        .thenComparingInt(TeacherDocumentBlockResponse::blockOrder))
                .flatMap(block -> parseImageAssetIds(block.imageRefs()).stream())
                .distinct()
                .limit(MAX_IMAGE_ASSETS_PER_HIT)
                .toList();
        if (nearbyAssetIds.isEmpty()) {
            return hit;
        }
        return new TeacherResourceBlockSearchResponse.Hit(
                hit.documentId(), hit.documentTitle(), hit.sourceType(), hit.permissionScope(), hit.blockId(),
                hit.blockType(), hit.blockOrder(), hit.chapter(), hit.section(), hit.pageNo(), hit.sourcePath(),
                hit.blockRole(), hit.graphTags(), hit.evidenceBlockIds(), hit.evidenceText(), hit.snippet(), hit.score(),
                nearbyAssetIds, List.of());
    }

    /** Null page numbers mean the source is unpaged and must fall back to block distance. */
    private static boolean samePage(Integer hitPage, Integer imagePage) {
        return hitPage != null && hitPage.equals(imagePage);
    }

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

    /**
     * Reads all parsed blocks from one visible document for an authorized agent.  The document is first resolved
     * through the same tenant/role/owner visibility gate used by search; callers never receive a filesystem path.
     */
    public List<TeacherDocumentBlockResponse> listVisibleBlocks(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        requireReaderRole(viewerRole);
        String normalizedDocumentId = textOrDefault(documentId, "");
        if (normalizedDocumentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        boolean visible = filteredDocuments(
                resourceStore.listVisible(tenantId, viewerRole, viewerSubjectId), TeacherResourceSearchFilter.EMPTY)
                .stream().anyMatch(document -> normalizedDocumentId.equals(document.documentId()));
        if (!visible) {
            throw new IllegalArgumentException("Teacher resource is not visible");
        }
        return blockStore.listByDocument(tenantId, normalizedDocumentId);
    }

    /** Tests the exact leading question number without letting formula or solution-line numerals cross-bind figures. */
    private static boolean matchesTopLevelQuestionNumber(String text, String expectedNumber) {
        Matcher matcher = TOP_LEVEL_QUESTION_NUMBER.matcher(textOrDefault(text, ""));
        return matcher.find() && expectedNumber.equals(matcher.group(1));
    }

    /** A subsequent numbered stem ends the source ownership window for the preceding question. */
    private static boolean matchesAnyTopLevelQuestionNumber(String text) {
        return TOP_LEVEL_QUESTION_NUMBER.matcher(textOrDefault(text, "")).find();
    }

    /**
     * Ensures only teacher/admin backend subjects can use this teacher resource endpoint.
     */
    private static void requireReaderRole(String viewerRole) {
        if (!TeacherResourceVisibilityPolicy.isReaderRole(viewerRole)) {
            throw new IllegalArgumentException("Teacher resource block search requires an authenticated reader role");
        }
    }

    private static List<TeacherResourceDocumentResponse> filteredDocuments(
            List<TeacherResourceDocumentResponse> documents,
            TeacherResourceSearchFilter filter) {
        return documents.stream()
                // A registered browser URL is not searchable evidence. This gates every caller (teacher UI, MCP, and
                // handout workflow) on the same owner-scoped download, parser, and vector-index completion in MySQL.
                .filter(TeacherResourceReadiness::isReady)
                // Runtime fixtures and benchmark imports are never user teaching material.  Filtering them at the
                // shared search boundary protects the UI, MCP callers, ordinary Q&A, and handout generation alike.
                .filter(document -> !isSyntheticOrBenchmarkSource(document))
                .filter(document -> filter.documentIds().isEmpty() || filter.documentIds().contains(document.documentId()))
                .filter(document -> filter.permissionScopes().isEmpty()
                        || filter.permissionScopes().contains(textOrDefault(document.permissionScope(), "").toUpperCase(Locale.ROOT)))
                .filter(document -> TeacherResourceLibraryResolver.matchesAny(document, filter.sourceTypes()))
                .toList();
    }

    private static boolean isSyntheticOrBenchmarkSource(TeacherResourceDocumentResponse document) {
        String text = normalizeText(String.join(" ",
                textOrDefault(document == null ? null : document.documentId(), ""),
                textOrDefault(document == null ? null : document.title(), ""),
                textOrDefault(document == null ? null : document.sourceIdentity(), ""),
                textOrDefault(document == null ? null : document.localPath(), ""),
                textOrDefault(document == null ? null : document.originalUrl(), "")));
        return text.contains("synthetic-natural-math-benchmark")
                || text.contains("benchmark-high-school-math")
                || text.contains("runtime-authored")
                || text.contains("runtime-teacher-resource")
                || text.contains("design-system-docs")
                || text.contains("knowledge-graph-spine")
                || text.contains("synthetic-natural")
                || text.contains("audit-feishu-rag")
                || text.matches(".*(?:^|[\\s/_-])runtime-[a-z0-9_-]+.*");
    }

    private boolean shouldUseRealTextbook(TeacherResourceSearchFilter filter) {
        if (!realTextbookAvailable()) {
            return false;
        }
        if (filter == null) {
            return false;
        }
        if (filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            // This endpoint owns teacher resources.  Textbook retrieval must be explicitly selected so an uploaded
            // document cannot be displaced by public pages before it reaches the BGE reranker.
            return false;
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

    /**
     * If the caller narrowed the search space to textbook only, there is no value in running teacher-resource stage
     * one first. The real textbook retriever is already the canonical source for that library and produces a cleaner
     * candidate pool for the final merge.
     */
    private static boolean isTextbookOnlyFilter(TeacherResourceSearchFilter filter) {
        if (filter == null) {
            return false;
        }
        if ((filter.permissionScopes() != null && !filter.permissionScopes().isEmpty())
                || (filter.tags() != null && !filter.tags().isEmpty())) {
            return false;
        }
        if (filter.sourceTypes() == null || filter.sourceTypes().isEmpty()) {
            return false;
        }
        return filter.sourceTypes().stream()
                .map(TeacherResourceBlockSearchService::normalizeText)
                .allMatch(selector -> "textbook".equals(selector) || "public_textbook".equals(selector));
    }

    private boolean realTextbookAvailable() {
        return textbookRetrievalService != null && textbookResourceProperties != null;
    }

    private static boolean matchesTags(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(document.title(), ""),
                TeacherResourceLibraryResolver.effectiveLibrary(document),
                textOrDefault(block.chapter(), ""),
                textOrDefault(block.section(), ""),
                textOrDefault(block.sourcePath(), ""),
                textOrDefault(block.blockRole(), ""),
                String.join(" ", parseStringArray(block.graphTagNamesJson())),
                textOrDefault(block.normalizedText(), block.rawText())));
        return tags.stream().map(TeacherResourceBlockSearchService::normalizeText).anyMatch(haystack::contains);
    }

    /**
     * Clamps query result size to keep the read path bounded.
     */
    private int clampLimit(int limit) {
        if (limit <= 0) {
            return searchProperties.defaultLimit();
        }
        return Math.min(limit, searchProperties.maxLimit());
    }

    /**
     * Normalizes a query before graph alignment and semantic retrieval.
     */
    private static String normalizeQuery(String query) {
        return normalizeText(textOrDefault(query, ""));
    }

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

    private static List<String> queryClauses(String normalizedQuery) {
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (String value : QUERY_CLAUSE_SPLITTER.split(normalizedQuery)) {
            String clause = normalizeText(textOrDefault(value, ""));
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(normalizedQuery);
        }
        return List.copyOf(clauses);
    }

    private static List<String> normalizedQueryParts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String tag = normalizeText(textOrDefault(value, ""));
            if (!tag.isBlank()) {
                normalized.add(tag);
            }
        }
        return List.copyOf(normalized);
    }

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

    /**
     * Keep one long non-routing clause even when graph alignment already matched another clause.
     *
     * <p>Teacher queries often start with a broad topic clause such as "数列方法怎么讲", then add the real
     * page- or block-discriminating evidence later in the sentence. If focus building keeps only the graph-matched
     * topic clause, stage-one document recall becomes generic again and sibling documents with the same module tag can
     * crowd out the actually correct handout. This helper preserves the normalized graph clue while still passing one
     * longest unseen user clause into semantic retrieval.</p>
     */
    private static void appendLongestRemainingClauses(
            LinkedHashSet<String> focusedParts,
            List<String> clauses,
            int maxClauses) {
        if (focusedParts.size() >= maxClauses) {
            return;
        }
        clauses.stream()
                .filter(clause -> !focusedParts.contains(clause))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .limit(Math.max(0, maxClauses - focusedParts.size()))
                .forEach(clause -> addFocusedPart(focusedParts, clause));
    }

    private static void addFocusedPart(LinkedHashSet<String> focusedParts, String candidate) {
        String normalized = normalizeText(textOrDefault(candidate, ""));
        if (normalized.isBlank()) {
            return;
        }
        for (String existing : focusedParts) {
            if (containsNormalized(existing, normalized) || containsNormalized(normalized, existing)) {
                return;
            }
        }
        focusedParts.add(normalized);
    }

    private static boolean containsNormalized(String haystack, String needle) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        String normalizedNeedle = normalizeText(textOrDefault(needle, ""));
        return !normalizedNeedle.isBlank() && normalizedHaystack.contains(normalizedNeedle);
    }

    /**
     * Teacher/agent callers often express the desired library inside the natural-language query but forget to pass the
     * dedicated {@code library} parameter. When that happens, running the full mixed teacher corpus first is both slow
     * and semantically wrong: the request already narrowed the evidence scope.
     *
     * <p>Only derive a library here when the structured filter did not already provide one, and only when the query
     * resolves to exactly one logical library family. This keeps the behavior generic and avoids overriding explicit
     * multi-library callers.</p>
     */
    private static TeacherResourceSearchFilter normalizeFilter(
            TeacherResourceSearchFilter filter,
            String normalizedQuery) {
        TeacherResourceSearchFilter base = filter == null ? TeacherResourceSearchFilter.EMPTY : filter;
        if (base.sourceTypes() != null && !base.sourceTypes().isEmpty()) {
            return base;
        }
        List<String> derivedLibraries = queryLibrarySelectors(normalizedQuery);
        if (derivedLibraries.isEmpty()) {
            return base;
        }
        return TeacherResourceSearchFilter.of(
                base.permissionScopes(),
                base.documentIds(),
                derivedLibraries,
                base.tags());
    }

    /**
     * Parses only logical library selectors from the user query. This is intentionally limited to broad source-family
     * names such as textbook/Feishu/QQ bundle/gaokao/mock; it must never parse knowledge-point content here.
     */
    private static List<String> queryLibrarySelectors(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        if (containsAny(normalizedQuery,
                "textbook",
                "教材库",
                "公共教材",
                "教材检索",
                "教材原文",
                "教材页",
                "课本原文",
                "课本",
                "教材")) {
            selectors.add("textbook");
        }
        if (containsAny(normalizedQuery,
                "feishu",
                "飞书")) {
            selectors.add("feishu");
        }
        if (containsAny(normalizedQuery,
                "qq_bundle",
                "qq专题",
                "qq 专题",
                "专题包",
                "qq")) {
            selectors.add("qq_bundle");
        }
        if (containsAny(normalizedQuery,
                "gaokao",
                "高考",
                "真题")) {
            selectors.add("gaokao");
        }
        if (containsAny(normalizedQuery,
                "mock_exam",
                "mock exam",
                "模拟题",
                "模拟卷",
                "模拟")) {
            selectors.add("mock_exam");
        }
        if (selectors.size() != 1) {
            return List.of();
        }
        return List.copyOf(selectors);
    }

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

    private static void addSearchTerm(LinkedHashSet<String> terms, String candidate) {
        String normalizedCandidate = normalizeText(textOrDefault(candidate, ""));
        if (normalizedCandidate.isBlank()) {
            return;
        }
        if (normalizedCandidate.length() == 1 && isAsciiWordChar(normalizedCandidate.charAt(0))) {
            /*
             * Single ASCII letters are almost always retrieval noise in real teacher queries: articles like "a",
             * variable fragments, and OCR leftovers would otherwise match nearly every Latin block and make filtered
             * library rejection impossible. Keep one-character CJK terms available, but drop one-character ASCII terms.
             */
            return;
        }
        terms.add(normalizedCandidate);
    }

    /**
     * Normalizes searchable text by case folding and whitespace compaction.
     */
    private static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Real teacher folders can carry very long paths, neighboring block windows, and image references. Truncate only
     * the rerank view so the worker gets the strongest semantic clues without timing out; the response still returns
     * the original evidence text and snippets elsewhere.
     */
    private static String truncateForRerank(String value, int maxChars) {
        String normalized = textOrDefault(value, "");
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars).strip() + "…";
    }

    private static void appendRerankLine(StringBuilder builder, String label, String value) {
        String normalizedValue = textOrDefault(value, "");
        if (normalizedValue.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(normalizedValue);
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Returns stripped text or fails when backend identity is missing.
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static boolean containsAny(String haystack, String... needles) {
        String normalizedHaystack = normalizeText(textOrDefault(haystack, ""));
        for (String needle : needles) {
            if (!needle.isBlank() && normalizedHaystack.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
    }

    private static boolean isCjkChar(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    private static List<String> parseStringArray(String json) {
        String value = textOrDefault(json, "[]");
        try {
            return OBJECT_MAPPER.readValue(value, STRING_LIST_TYPE).stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::strip)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * imageRefs is a JSON object array because each asset carries page and source metadata. Keep this parser distinct
     * from graph-tag string arrays; treating it as a String list silently discarded asset ids after a successful hit.
     */
    private static List<String> parseImageAssetIds(String json) {
        try {
            JsonNode values = OBJECT_MAPPER.readTree(textOrDefault(json, "[]"));
            if (!values.isArray()) {
                return List.of();
            }
            List<String> assetIds = new ArrayList<>();
            for (JsonNode value : values) {
                String assetId = value.path("assetId").asText("").strip();
                if (!assetId.isBlank()) {
                    assetIds.add(assetId);
                }
            }
            return List.copyOf(assetIds);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Formula JSON stays structured in storage, while reranking receives only bounded canonical evidence. */
    private static String formulaEvidence(String formulaRefs) {
        try {
            JsonNode formulas = OBJECT_MAPPER.readTree(textOrDefault(formulaRefs, "[]"));
            if (!formulas.isArray()) {
                return "";
            }
            List<String> values = new ArrayList<>();
            for (JsonNode formula : formulas) {
                String plainText = formula.path("plainText").asText("").strip();
                String latex = formula.path("latex").asText("").strip();
                if (!plainText.isBlank()) {
                    values.add(plainText);
                }
                if (!latex.isBlank()) {
                    values.add(latex);
                }
            }
            return String.join(" ", values);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String blockEvidenceText(TeacherDocumentBlockResponse block) {
        return normalizeText(String.join(
                " ",
                textOrDefault(block.rawText(), block.normalizedText()),
                formulaEvidence(block.formulaRefs())));
    }

    private static String blockKey(String documentId, String blockId) {
        return documentId + ":" + blockId;
    }

    private record BlockContext(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            String searchableText,
            String sourcePath,
            String blockRole,
            List<String> graphTags,
            List<String> graphNodeIds) {
    }

    private record DocumentCandidate(
            TeacherResourceDocumentResponse document,
            List<BlockContext> blocks,
            double coarseScore) {
    }

    private record BlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            double rerankScore,
            int lexicalMatches,
            double documentCoarseScore,
            double vectorSemanticScore) {
    }

    private record EvidenceWindow(
            List<String> blockIds,
            String text) {
    }

    private record StageTwoBlockCandidate(
            TeacherResourceDocumentResponse document,
            BlockContext block,
            List<BlockContext> documentBlocks) {
    }

    private record MergeCandidate(
            TeacherResourceBlockSearchResponse.Hit hit,
            double rerankScore,
            int lexicalMatches) {
        private double sourceScore() {
            return hit.score();
        }
    }

    private record FocusedSearchQuery(
            String semanticQuery,
            String[] terms) {
    }

    private record VectorCoarseRecall(
            Map<String, Double> scoreByKey,
            List<String> candidateDocumentIds) {
        private static final VectorCoarseRecall EMPTY = new VectorCoarseRecall(Map.of(), List.of());
    }

    /** Opaque reference that the authenticated teacher-resource detail endpoint can safely expand. */
    public record CanonicalReference(String documentId, String blockId, String documentTitle) {
    }

    /** Internal ranked candidate; source and block scores remain separate to keep identity evidence auditable. */
    private record CanonicalCandidate(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            int sourceScore,
            int blockScore) {
    }

}

