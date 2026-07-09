package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TextbookRetrievalService {

    private static final long LOAD_FAILURE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String SEARCH_PIPELINE_VERSION = "two_stage_doc_page_v3_focus_rerank";
    private static final Pattern QUERY_CLAUSE_SPLITTER = Pattern.compile("[\\r\\n,，。；;：:！？!?()（）\\[\\]【】]+");
    private static final int MAX_FOCUSED_QUERY_CHARS = 120;
    private static final int MAX_FOCUSED_CLAUSES = 2;
    private static final int MAX_FOCUSED_TAGS = 4;
    /**
     * Worker I/O budgets for textbook rerank.
     *
     * <p>These bounds only keep the cross-encoder payload within a predictable latency budget on the real local
     * worker. They are not ranking weights; final ordering still comes from semantic rerank.</p>
     */
    private static final TextbookRerankBudget RERANK_BUDGET = TextbookRerankBudget.defaults();

    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final LocalTextbookBm25SearchEngine searchEngine;
    private final RetrievalAuditSink auditSink;
    private final TextbookSearchCache searchCache;
    private final RedisTextbookSearchCacheProperties searchCacheProperties;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TextbookPageImageService pageImageService;
    private final VectorIndexService vectorIndexService;
    /**
     * 语料加载互斥锁：防止缓存未命中时多个请求同时读取大批量教材文件，降低缓存击穿风险。
     */
    private final Object corpusLoadLock = new Object();
    /**
     * 最近一次成功加载的教材语料快照：源文件临时异常时可作为旧缓存兜底，降低缓存雪崩影响。
     */
    private volatile CachedTextbookCorpus cachedCorpus;
    /**
     * 最近一次语料加载失败状态：用于失败冷却，避免底层文件或存储异常时每个请求都重复触发昂贵加载。
     */
    private volatile CorpusLoadFailure lastLoadFailure;

    @Autowired
    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink,
            TextbookSearchCache searchCache,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TextbookPageImageService pageImageService,
            VectorIndexService vectorIndexService) {
        this.catalogReader = Objects.requireNonNull(catalogReader, "catalogReader is required");
        this.chunkReader = Objects.requireNonNull(chunkReader, "chunkReader is required");
        this.searchEngine = Objects.requireNonNull(searchEngine, "searchEngine is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
        this.searchCache = Objects.requireNonNull(searchCache, "searchCache is required");
        this.searchCacheProperties = Objects.requireNonNull(searchCacheProperties, "searchCacheProperties is required");
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.pageImageService = Objects.requireNonNull(pageImageService, "pageImageService is required");
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
    }

    /**
     * 使用默认教材检索上下文执行搜索，主要供单元测试和内部调用使用。
     */
    public TextbookSearchResponse search(Path processedBooksRoot, TextbookSearchRequest request) {
        return search(processedBooksRoot, request, RetrievalRequestContext.defaultTextbookSearch());
    }

    /**
     * 执行 BM25-first 教材检索，并同步写入检索审计事件。
     */
    public TextbookSearchResponse search(
            Path processedBooksRoot,
            TextbookSearchRequest request,
            RetrievalRequestContext requestContext) {
        RetrievalRequestContext normalizedContext = requestContext == null
                ? RetrievalRequestContext.defaultTextbookSearch().normalize()
                : requestContext.normalize();
        String queryId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        CachedTextbookCorpus corpus = loadCorpus(normalizedRoot);
        String cacheKey = searchCacheKey(corpus, request);
        TextbookSearchCache.CachedTextbookSearch cached = searchCache.find(cacheKey).orElse(null);
        TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph = graphAlignmentService.alignQuery(
                normalizedContext.tenantId(),
                normalizedContext.subjectType(),
                normalizedContext.subjectId(),
                request.query());
        String focusedQuery = focusedTextbookQuery(request.query(), queryGraph);
        List<TextbookSearchHit> hits = cached == null
                ? rerankedHits(focusedQuery, request.limit(), corpus.chunks(), queryGraph)
                : cached.hits();
        hits = attachControlledPageImageUris(hits);
        if (cached == null && !hits.isEmpty()) {
            searchCache.put(
                    cacheKey,
                    new TextbookSearchCache.CachedTextbookSearch(
                            request.query(),
                            request.limit(),
                            SEARCH_PIPELINE_VERSION,
                            hits.size(),
                            hits),
                    searchCacheProperties.normalizedTtl());
        }
        int elapsedMs = elapsedMs(startedAtNanos);
        TextbookSearchResponse response = new TextbookSearchResponse(
                queryId,
                request.query(),
                request.limit(),
                cached == null ? SEARCH_PIPELINE_VERSION : "redis_cache_" + SEARCH_PIPELINE_VERSION,
                hits.size(),
                hits);
        auditSink.record(RetrievalAuditEvent.from(queryId, request, response, elapsedMs, normalizedContext));
        return response;
    }

    /**
     * Agent-assembled textbook queries often mix real topic intent with routing/control clauses such as library hints,
     * evidence requirements, or other workflow instructions. Feeding that entire sentence into textbook BM25 explodes
     * generic n-gram matches across many pages and then wastes semantic rerank on a noisy candidate pool.
     *
     * <p>Keep this focus builder corpus-agnostic: it never strips benchmark-specific phrases or hardcodes textbook
     * topics. Instead it prefers the clauses that align with normalized graph tags and falls back to the longest
     * content-bearing clauses when graph alignment is unavailable.</p>
     */
    private static String focusedTextbookQuery(
            String rawQuery,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        String normalized = normalizeQueryText(rawQuery);
        if (normalized.isBlank()) {
            return normalized;
        }
        List<String> clauses = splitQueryClauses(normalized);
        List<String> primaryTags = normalizeQueryParts(queryGraph == null ? List.of() : queryGraph.primaryTagNames());
        List<String> expandedTags = normalizeQueryParts(queryGraph == null ? List.of() : queryGraph.expandedTagNames());

        LinkedHashSet<String> focusedParts = new LinkedHashSet<>();
        clauses.stream()
                .map(clause -> new QueryClauseCandidate(clause, clauseScore(clause, primaryTags, expandedTags)))
                .filter(candidate -> candidate.score() > 0.0d)
                .sorted(Comparator.comparingDouble(QueryClauseCandidate::score)
                        .reversed()
                        .thenComparing(Comparator.comparingInt((QueryClauseCandidate candidate) -> candidate.text().length()).reversed())
                        .thenComparing(QueryClauseCandidate::text))
                .limit(MAX_FOCUSED_CLAUSES)
                .map(QueryClauseCandidate::text)
                .forEach(focusedParts::add);
        if (focusedParts.isEmpty()) {
            clauses.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                    .limit(MAX_FOCUSED_CLAUSES)
                    .forEach(focusedParts::add);
        }
        primaryTags.stream().limit(MAX_FOCUSED_TAGS).forEach(focusedParts::add);
        if (focusedParts.size() < MAX_FOCUSED_CLAUSES + 1) {
            expandedTags.stream()
                    .filter(tag -> focusedParts.stream().noneMatch(existing -> compact(existing).contains(compact(tag))))
                    .limit(MAX_FOCUSED_TAGS)
                    .forEach(focusedParts::add);
        }
        String focused = truncateForRerank(String.join(" ", focusedParts), MAX_FOCUSED_QUERY_CHARS);
        return focused.isBlank() ? normalized : focused;
    }

    private static List<String> splitQueryClauses(String query) {
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (String part : QUERY_CLAUSE_SPLITTER.split(query)) {
            String normalized = normalizeQueryText(part);
            if (!normalized.isBlank()) {
                clauses.add(normalized);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(query);
        }
        return List.copyOf(clauses);
    }

    private static List<String> normalizeQueryParts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = normalizeQueryText(value);
            if (!cleaned.isBlank()) {
                normalized.add(cleaned);
            }
        }
        return List.copyOf(normalized);
    }

    private static double clauseScore(String clause, List<String> primaryTags, List<String> expandedTags) {
        String compactClause = compact(clause);
        double score = Math.min(clause.length(), 60) / 60.0d;
        for (String tag : primaryTags) {
            if (!tag.isBlank() && compactClause.contains(compact(tag))) {
                score += 8.0d;
            }
        }
        for (String tag : expandedTags) {
            if (!tag.isBlank() && compactClause.contains(compact(tag))) {
                score += 3.0d;
            }
        }
        return score;
    }

    private static String normalizeQueryText(String value) {
        return String.valueOf(value == null ? "" : value)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String compact(String value) {
        return normalizeQueryText(value).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The lexical engine is now only the coarse-recall stage for textbooks. It may still use BM25/metadata signals to
     * avoid scanning unrelated pages, but it no longer decides the final rank returned to callers. Final ordering is
     * always rebuilt here with the configured rerank model so textbook retrieval follows the same semantic-first
     * discipline as teacher-resource retrieval.
     */
    private List<TextbookSearchHit> rerankedHits(
            String query,
            int limit,
            List<TextbookChunk> chunks,
            TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
        int safeLimit = Math.max(1, limit);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        /*
         * Request the full positive lexical candidate set instead of locking quality to a multiplier/floor constant.
         * The lexical engine now only decides which pages are plausible enough to enter semantic rerank, not their
         * final order.
         */
        List<TextbookSearchHit> coarseHits = searchEngine.search(query, chunks, chunks.size(), queryGraph);
        if (coarseHits.isEmpty()) {
            return List.of();
        }
        Map<String, List<TextbookSearchHit>> hitsByDocId = new LinkedHashMap<>();
        for (TextbookSearchHit hit : coarseHits) {
            hitsByDocId.computeIfAbsent(hit.docId(), ignored -> new ArrayList<>()).add(hit);
        }
        Map<String, List<TextbookSearchHit>> coarseDocumentCandidates = topLexicalDocumentCandidates(hitsByDocId, safeLimit);
        Map<String, List<TextbookSearchHit>> supportHitsByDocId = cappedSupportHitsByDocId(coarseDocumentCandidates);
        Map<String, Double> documentSemanticScores = semanticScoreByKey(query, documentCandidateTexts(supportHitsByDocId));
        List<String> rankedDocIds = rankedDocumentIds(supportHitsByDocId, documentSemanticScores, safeLimit);
        List<TextbookSearchHit> pageCandidates = pageCandidates(rankedDocIds, supportHitsByDocId);
        Map<String, Double> pageSemanticScores = semanticScoreByKey(query, pageCandidateTexts(pageCandidates));
        return pageCandidates.stream()
                .map(hit -> new TextbookPageCandidate(
                        hit,
                        pageSemanticScores.getOrDefault(hit.chunkId(), hit.score()),
                        documentSemanticScores.getOrDefault(hit.docId(), hit.score())))
                .sorted(Comparator.<TextbookPageCandidate>comparingDouble(TextbookPageCandidate::pageSemanticScore).reversed()
                        .thenComparing(Comparator.comparingDouble(TextbookPageCandidate::documentSemanticScore).reversed())
                        .thenComparing(Comparator.comparingDouble((TextbookPageCandidate candidate) -> candidate.hit().score()).reversed())
                        .thenComparing(candidate -> candidate.hit().docId())
                        .thenComparingInt(candidate -> candidate.hit().pageNo()))
                .limit(safeLimit)
                .map(candidate -> withScore(candidate.hit(), candidate.pageSemanticScore()))
                .toList();
    }

    /**
     * Document-level coarse retrieval already produced a global lexical ordering. Keep only the strongest few book
     * buckets before invoking the cross-encoder; otherwise a top-5 page query ends up reranking dozens of books that
     * can never all survive into the final answer.
     */
    private static Map<String, List<TextbookSearchHit>> topLexicalDocumentCandidates(
            Map<String, List<TextbookSearchHit>> hitsByDocId,
            int safeLimit) {
        int docLimit = Math.max(1, Math.min(RERANK_BUDGET.maxDocumentCandidates(), safeLimit));
        Map<String, List<TextbookSearchHit>> candidates = new LinkedHashMap<>();
        hitsByDocId.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<TextbookSearchHit>>>comparingDouble(
                                entry -> entry.getValue().isEmpty() ? 0.0d : entry.getValue().getFirst().score())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(docLimit)
                .forEach(entry -> candidates.put(entry.getKey(), entry.getValue()));
        return candidates;
    }

    /**
     * Page rerank only needs the strongest sibling pages inside each candidate book. Limiting this window prevents a
     * single large chapter from exhausting the rerank budget before other candidate books can compete.
     */
    private static Map<String, List<TextbookSearchHit>> cappedSupportHitsByDocId(
            Map<String, List<TextbookSearchHit>> hitsByDocId) {
        Map<String, List<TextbookSearchHit>> capped = new LinkedHashMap<>();
        for (Map.Entry<String, List<TextbookSearchHit>> entry : hitsByDocId.entrySet()) {
            capped.put(entry.getKey(), entry.getValue().stream().limit(RERANK_BUDGET.maxPagesPerDocument()).toList());
        }
        return capped;
    }

    /**
     * The request limit itself defines the document candidate boundary. For a top-N page response, more than N books
     * cannot contribute unique final winners, so we keep the coarse stage bounded by caller intent instead of opaque
     * multipliers.
     */
    private static List<String> rankedDocumentIds(
            Map<String, List<TextbookSearchHit>> supportHitsByDocId,
            Map<String, Double> documentSemanticScores,
            int safeLimit) {
        return supportHitsByDocId.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<TextbookSearchHit>>>comparingDouble(
                                entry -> documentSemanticScores.getOrDefault(entry.getKey(), 0.0d))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (Map.Entry<String, List<TextbookSearchHit>> entry) -> entry.getValue().isEmpty()
                                        ? 0.0d
                                        : entry.getValue().getFirst().score()).reversed())
                        .thenComparing(Map.Entry::getKey))
                .limit(safeLimit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<TextbookSearchHit> pageCandidates(
            List<String> rankedDocIds,
            Map<String, List<TextbookSearchHit>> supportHitsByDocId) {
        List<TextbookSearchHit> candidates = new ArrayList<>();
        for (String docId : rankedDocIds) {
            candidates.addAll(supportHitsByDocId.getOrDefault(docId, List.of()));
        }
        return candidates;
    }

    /**
     * Uses the configured rerank endpoint when available and falls back inside VectorIndexService to embedding cosine
     * similarity. Keep the fallback centralized there so textbook retrieval does not grow its own heuristic score path.
     */
    private Map<String, Double> semanticScoreByKey(String query, Map<String, String> candidateTexts) {
        if (candidateTexts.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(candidateTexts.keySet());
        List<String> texts = keys.stream().map(candidateTexts::get).toList();
        List<Double> scores = vectorIndexService.rerankTexts(query, texts);
        Map<String, Double> scoreByKey = new LinkedHashMap<>();
        for (int index = 0; index < keys.size() && index < scores.size(); index += 1) {
            scoreByKey.put(keys.get(index), scores.get(index));
        }
        return Map.copyOf(scoreByKey);
    }

    private static Map<String, String> documentCandidateTexts(Map<String, List<TextbookSearchHit>> hitsByDocId) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, List<TextbookSearchHit>> entry : hitsByDocId.entrySet()) {
            texts.put(entry.getKey(), semanticDocumentText(entry.getValue()));
        }
        return texts;
    }

    private static Map<String, String> pageCandidateTexts(List<TextbookSearchHit> hits) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (TextbookSearchHit hit : hits) {
            texts.put(hit.chunkId(), semanticPageText(hit));
        }
        return texts;
    }

    /**
     * Document-level rerank asks a simple question: "does this textbook contain the right evidence somewhere in these
     * candidate pages?" We therefore concatenate only the already-recalled pages from that book instead of inventing a
     * new document summary or relying on fragile filename features.
     *
     * <p>Do not send whole OCR pages into the reranker. Real textbook pages can contain large OCR noise and long
     * formula dumps; once several candidate pages are concatenated, the worker times out before producing a score.
     * Keep a bounded semantic digest here so rerank latency stays predictable while still exposing chapter/section and
     * the most relevant snippet text.</p>
     */
    private static String semanticDocumentText(List<TextbookSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        TextbookSearchHit first = hits.getFirst();
        appendLine(builder, first.bookName());
        appendLine(builder, first.volume());
        for (TextbookSearchHit hit : hits) {
            appendLine(builder, semanticPageText(hit));
            if (builder.length() >= RERANK_BUDGET.documentTextChars()) {
                break;
            }
        }
        return truncateForRerank(builder.toString(), RERANK_BUDGET.documentTextChars());
    }

    private static String semanticPageText(TextbookSearchHit hit) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, hit.bookName());
        appendLine(builder, hit.volume());
        appendLine(builder, String.join(" / ", hit.chapterPath() == null ? List.of() : hit.chapterPath()));
        appendLine(builder, hit.sectionTitle());
        appendLine(builder, hit.printedPageNo());
        appendLine(builder, truncateForRerank(hit.textSnippet(), RERANK_BUDGET.pageTextChars()));
        appendLine(builder, truncateForRerank(hit.formulaText(), RERANK_BUDGET.formulaTextChars()));
        return builder.toString();
    }

    /**
     * Rerank quality depends on sending the strongest semantic clue, not the entire raw OCR payload. Truncate only for
     * model I/O control; caller-visible evidence and snippets remain untouched in the final response.
     */
    private static String truncateForRerank(String value, int maxChars) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars).strip() + "…";
    }

    private static void appendLine(StringBuilder builder, String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(normalized);
    }

    private static TextbookSearchHit withScore(TextbookSearchHit hit, double score) {
        return new TextbookSearchHit(
                hit.chunkId(),
                score,
                hit.retrievalStrategy(),
                hit.docId(),
                hit.bookName(),
                hit.volume(),
                hit.chapterPath(),
                hit.pageNo(),
                hit.printedPageNo(),
                hit.sectionTitle(),
                hit.textSnippet(),
                hit.formulaText(),
                hit.sourcePageImage(),
                hit.pageQualityLabel(),
                hit.pageImageUri());
    }

    /**
     * 教材原始 chunk 只保存相对图片路径；真正返回给前端和 AI 的必须是后端受控 URI，避免调用方拼接本地路径。
     */
    private List<TextbookSearchHit> attachControlledPageImageUris(List<TextbookSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(this::attachControlledPageImageUri)
                .toList();
    }

    /**
     * 只在页码有效且当前命中还没携带 URI 时补地址，这样既兼容旧缓存，也避免覆盖上游已经显式指定的值。
     */
    private TextbookSearchHit attachControlledPageImageUri(TextbookSearchHit hit) {
        if (hit == null) {
            return null;
        }
        if (hit.pageImageUri() != null && !hit.pageImageUri().isBlank()) {
            return hit;
        }
        if (hit.pageNo() <= 0 || hit.docId() == null || hit.docId().isBlank()) {
            return hit;
        }
        return hit.withPageImageUri(pageImageService.pageImageUri(hit.docId(), hit.pageNo()));
    }

    /**
     * 加载教材语料缓存；当多个请求同时缓存未命中时，只允许一个线程执行实际加载。
     */
    private CachedTextbookCorpus loadCorpus(Path processedBooksRoot) {
        try {
            return loadCorpusOrThrow(processedBooksRoot);
        } catch (RuntimeException e) {
            return recoverFromLoadFailure(processedBooksRoot, e);
        }
    }

    /**
     * 执行真实语料加载流程：先校验失败冷却，再计算源文件签名，最后在锁内单飞加载。
     */
    private CachedTextbookCorpus loadCorpusOrThrow(Path processedBooksRoot) {
        rejectDuringFailureCooldown(processedBooksRoot);
        List<TextbookCatalogItem> books = catalogReader.read(processedBooksRoot.resolve("catalog.jsonl"));
        List<Path> chunkPaths = books.stream()
                .map(book -> chunksPath(processedBooksRoot, book))
                .toList();
        List<SourceFileSignature> signatures = sourceSignatures(processedBooksRoot.resolve("catalog.jsonl"), chunkPaths);
        CachedTextbookCorpus snapshot = cachedCorpus;
        if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
            return snapshot;
        }

        synchronized (corpusLoadLock) {
            rejectDuringFailureCooldown(processedBooksRoot);
            snapshot = cachedCorpus;
            if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
                return snapshot;
            }
            CachedTextbookCorpus loaded = readCorpus(processedBooksRoot, signatures, chunkPaths);
            cachedCorpus = loaded;
            lastLoadFailure = null;
            return loaded;
        }
    }

    /**
     * 判断缓存快照是否仍对应当前根目录和源文件签名。
     */
    private static boolean isCurrentSnapshot(
            CachedTextbookCorpus snapshot,
            Path processedBooksRoot,
            List<SourceFileSignature> signatures) {
        return snapshot != null
                && snapshot.processedBooksRoot().equals(processedBooksRoot)
                && snapshot.signatures().equals(signatures);
    }

    /**
     * 加载失败后的恢复策略：同根目录旧缓存优先返回；没有旧缓存时记录失败并抛出可观测异常。
     */
    private CachedTextbookCorpus recoverFromLoadFailure(Path processedBooksRoot, RuntimeException failure) {
        CachedTextbookCorpus snapshot = cachedCorpus;
        lastLoadFailure = new CorpusLoadFailure(processedBooksRoot, System.nanoTime(), failure.getMessage());
        if (snapshot != null && snapshot.processedBooksRoot().equals(processedBooksRoot)) {
            return snapshot;
        }
        throw new IllegalStateException("Failed to load textbook corpus: " + failure.getMessage(), failure);
    }

    /**
     * 冷却期内阻断重复加载：无旧缓存时快速失败，有旧缓存时由上层恢复逻辑返回旧快照。
     */
    private void rejectDuringFailureCooldown(Path processedBooksRoot) {
        CorpusLoadFailure failure = lastLoadFailure;
        if (failure == null || !failure.processedBooksRoot().equals(processedBooksRoot)) {
            return;
        }
        long elapsedNanos = System.nanoTime() - failure.failedAtNanos();
        if (elapsedNanos < LOAD_FAILURE_COOLDOWN_NANOS) {
            throw new IllegalStateException("Textbook corpus load cooldown active after failure: " + failure.reason());
        }
    }

    /**
     * 从 chunk 文件读取完整教材语料，并封装为不可变缓存快照。
     */
    private CachedTextbookCorpus readCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            List<Path> chunkPaths) {
        List<TextbookChunk> chunks = new ArrayList<>();
        for (Path chunksPath : chunkPaths) {
            chunks.addAll(chunkReader.read(chunksPath));
        }
            CachedTextbookCorpus loaded = new CachedTextbookCorpus(
                    processedBooksRoot,
                    signatures,
                    corpusSignatureHash(processedBooksRoot, signatures),
                    List.copyOf(chunks));
            return loaded;
    }

    /**
     * 计算 catalog 和 chunk 文件签名，用于判断缓存是否仍然有效。
     */
    private static List<SourceFileSignature> sourceSignatures(Path catalogPath, List<Path> chunkPaths) {
        List<SourceFileSignature> signatures = new ArrayList<>();
        signatures.add(sourceSignature(catalogPath));
        chunkPaths.stream().map(TextbookRetrievalService::sourceSignature).forEach(signatures::add);
        return List.copyOf(signatures);
    }

    /**
     * 读取单个源文件的大小和最后修改时间作为缓存签名。
     */
    private static SourceFileSignature sourceSignature(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return new SourceFileSignature(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint textbook source file: " + path, e);
        }
    }

    /**
     * 根据 catalog 中的 book_root 找到优先使用的 AI chunk 文件。
     */
    private static Path chunksPath(Path processedBooksRoot, TextbookCatalogItem book) {
        Path bookRoot = Paths.get(book.bookRoot());
        if (!bookRoot.isAbsolute()) {
            bookRoot = processedBooksRoot.resolve(bookRoot);
        }
        Path aiChunks = bookRoot.resolve("jsonl_ai/chunks.jsonl");
        if (Files.exists(aiChunks)) {
            return aiChunks;
        }
        Path textChunks = bookRoot.resolve("jsonl/chunks.jsonl");
        if (Files.exists(textChunks)) {
            return textChunks;
        }
        throw new IllegalStateException("Missing textbook chunks for book " + book.docId() + ": " + bookRoot);
    }

    /**
     * 把纳秒耗时转换为毫秒，并避免整数溢出。
     */
    private static int elapsedMs(long startedAtNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private static String searchCacheKey(CachedTextbookCorpus corpus, TextbookSearchRequest request) {
        return sha256(String.join("|",
                SEARCH_PIPELINE_VERSION,
                corpus.processedBooksRoot().toString(),
                corpus.signatureHash(),
                String.valueOf(request.limit()),
                request.query()));
    }

    private static String corpusSignatureHash(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures) {
        StringBuilder payload = new StringBuilder(processedBooksRoot.toString());
        for (SourceFileSignature signature : signatures) {
            payload.append('|')
                    .append(signature.path())
                    .append(':')
                    .append(signature.size())
                    .append(':')
                    .append(signature.lastModifiedNanos());
        }
        return sha256(payload.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record SourceFileSignature(Path path, long size, long lastModifiedNanos) {
    }

    /**
     * 教材语料加载失败状态。
     *
     * @param processedBooksRoot 失败对应的教材处理产物根目录。
     * @param failedAtNanos 使用 System.nanoTime 记录的失败时间，用于计算冷却窗口。
     * @param reason 最近一次失败原因，便于日志、测试和接口错误排查。
     */
    private record CorpusLoadFailure(Path processedBooksRoot, long failedAtNanos, String reason) {
    }

    private record CachedTextbookCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            String signatureHash,
            List<TextbookChunk> chunks) {
    }

    private record TextbookPageCandidate(
            TextbookSearchHit hit,
            double pageSemanticScore,
            double documentSemanticScore) {
    }

    private record QueryClauseCandidate(String text, double score) {
    }

    private record TextbookRerankBudget(
            int maxDocumentCandidates,
            int maxPagesPerDocument,
            int pageTextChars,
            int formulaTextChars,
            int documentTextChars) {

        private static TextbookRerankBudget defaults() {
            return new TextbookRerankBudget(5, 2, 720, 240, 2200);
        }
    }
}
