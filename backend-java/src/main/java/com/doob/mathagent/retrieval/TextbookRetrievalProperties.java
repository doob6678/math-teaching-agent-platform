package com.doob.mathagent.retrieval;

import org.springframework.core.env.Environment;

/**
 * Runtime limits for the textbook document-to-page retrieval pipeline.
 *
 * <p>These values control candidate admission and worker I/O only. They deliberately do not define a hidden score
 * fusion: BM25 admits documents in its stable lexical order, BGE/CLIP preserve independent rescue evidence inside
 * each admitted document, and the final page order comes from the cross-encoder. Keeping the limits here makes
 * latency tuning explicit and prevents the retrieval service from accumulating undocumented numeric constants.</p>
 */
public record TextbookRetrievalProperties(QueryFocus queryFocus, RerankBudget rerank) {

    /** One complete production evidence window: three books times three pages. */
    private static final int DEFAULT_MAX_DOCUMENT_CANDIDATES = 3;
    private static final int DEFAULT_MAX_PAGES_PER_DOCUMENT = 3;
    /** 粗召回池比最终重排池更宽，避免正确教材在进入 Cross-Encoder 前被硬截断。 */
    private static final int DEFAULT_MAX_COARSE_DOCUMENT_CANDIDATES = 5;
    /** BGE 粗召回保留更多页，最终仍由 maxPagesPerDocument 控制送入重排的页数。 */
    private static final int DEFAULT_MAX_COARSE_PAGES_PER_DOCUMENT = 5;
    /** RRF 的平滑常数是部署参数，避免把排名融合常数散落在检索实现中。 */
    private static final int DEFAULT_COARSE_RRF_K = 60;
    private static final int DEFAULT_MAX_RERANK_DOCUMENTS = DEFAULT_MAX_DOCUMENT_CANDIDATES;
    private static final int DEFAULT_MAX_PAGE_CANDIDATES = DEFAULT_MAX_RERANK_DOCUMENTS * DEFAULT_MAX_PAGES_PER_DOCUMENT;

    public TextbookRetrievalProperties {
        queryFocus = queryFocus == null ? QueryFocus.defaults() : queryFocus;
        rerank = rerank == null ? RerankBudget.defaults() : rerank;
    }

    /** Provides defaults for direct construction in focused tests and non-Spring tools. */
    public static TextbookRetrievalProperties defaults() {
        return new TextbookRetrievalProperties(QueryFocus.defaults(), RerankBudget.defaults());
    }

    /** Reads optional deployment overrides without making tests depend on Spring configuration files. */
    public static TextbookRetrievalProperties fromSpringEnvironment(Environment environment) {
        TextbookRetrievalProperties defaults = defaults();
        QueryFocus focus = defaults.queryFocus();
        RerankBudget budget = defaults.rerank();
        return new TextbookRetrievalProperties(
                new QueryFocus(
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.max-query-chars"), focus.maxQueryChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.max-clauses"), focus.maxClauses()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.max-graph-tags"), focus.maxGraphTags()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.lexical-first-max-query-chars"), focus.lexicalFirstMaxQueryChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.semantic-title-context-limit"), focus.semanticTitleContextLimit()),
                        booleanOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.dynamic-route-enabled"), focus.dynamicRouteEnabled()),
                        booleanOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.normalize-agent-wrapper-enabled"), focus.normalizeAgentWrapperEnabled())),
                new RerankBudget(
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-document-candidates"), budget.maxDocumentCandidates()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-pages-per-document"), budget.maxPagesPerDocument()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-rerank-documents"), budget.maxRerankDocuments()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-page-candidates"), budget.maxPageCandidates()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-coarse-document-candidates"), budget.maxCoarseDocumentCandidates()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-coarse-pages-per-document"), budget.maxCoarsePagesPerDocument()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.coarse-rrf-k"), budget.coarseRrfK()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.page-text-chars"), budget.pageTextChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.formula-text-chars"), budget.formulaTextChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.document-text-chars"), budget.documentTextChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.lexical-dominant-pages-per-document"), budget.lexicalDominantPagesPerDocument())));
    }

    /** Limits for removing routing noise before semantic retrieval. */
    public record QueryFocus(
            int maxQueryChars,
            int maxClauses,
            int maxGraphTags,
            int lexicalFirstMaxQueryChars,
            int semanticTitleContextLimit,
            boolean dynamicRouteEnabled,
            boolean normalizeAgentWrapperEnabled) {
        public static QueryFocus defaults() {
            return new QueryFocus(120, 2, 4, 24, 3, false, false);
        }

        public QueryFocus {
            maxQueryChars = Math.max(32, maxQueryChars);
            maxClauses = Math.max(1, maxClauses);
            maxGraphTags = Math.max(0, maxGraphTags);
            lexicalFirstMaxQueryChars = Math.max(8, Math.min(lexicalFirstMaxQueryChars, maxQueryChars));
            semanticTitleContextLimit = Math.max(0, semanticTitleContextLimit);
        }
    }

    /** Bounded payload and candidate windows for the real local rerank endpoint. */
    public record RerankBudget(
            int maxDocumentCandidates,
            int maxPagesPerDocument,
            int maxRerankDocuments,
            int maxPageCandidates,
            int maxCoarseDocumentCandidates,
            int maxCoarsePagesPerDocument,
            int coarseRrfK,
            int pageTextChars,
            int formulaTextChars,
            int documentTextChars,
            int lexicalDominantPagesPerDocument) {
        public static RerankBudget defaults() {
            return new RerankBudget(
                    DEFAULT_MAX_DOCUMENT_CANDIDATES,
                    DEFAULT_MAX_PAGES_PER_DOCUMENT,
                    DEFAULT_MAX_RERANK_DOCUMENTS,
                    DEFAULT_MAX_PAGE_CANDIDATES,
                    DEFAULT_MAX_COARSE_DOCUMENT_CANDIDATES,
                    DEFAULT_MAX_COARSE_PAGES_PER_DOCUMENT,
                    DEFAULT_COARSE_RRF_K,
                    120,
                    40,
                    320,
                    5);
        }

        public RerankBudget {
            maxDocumentCandidates = Math.max(1, maxDocumentCandidates);
            maxPagesPerDocument = Math.max(1, maxPagesPerDocument);
            maxRerankDocuments = Math.max(1, Math.min(maxRerankDocuments, maxDocumentCandidates));
            maxPageCandidates = Math.max(1, maxPageCandidates);
            maxCoarseDocumentCandidates = Math.max(maxDocumentCandidates, maxCoarseDocumentCandidates);
            maxCoarsePagesPerDocument = Math.max(maxPagesPerDocument, maxCoarsePagesPerDocument);
            coarseRrfK = Math.max(1, coarseRrfK);
            pageTextChars = Math.max(32, pageTextChars);
            formulaTextChars = Math.max(0, formulaTextChars);
            documentTextChars = Math.max(pageTextChars, documentTextChars);
            lexicalDominantPagesPerDocument = Math.max(maxPagesPerDocument, lexicalDominantPagesPerDocument);
        }

        /**
         * The public response limit controls only returned evidence.  This
         * separate configured window controls the cross-encoder comparison,
         * so a caller asking for three results does not silently prevent the
         * reranker from seeing the remaining candidate pages.
         */
        public int pageCandidateLimit() {
            return maxPageCandidates;
        }

        /** 粗召回请求的页数上限；它不等于最终 Cross-Encoder 的页数上限。 */
        public int coarsePageCandidateLimit() {
            long product = (long) maxCoarseDocumentCandidates * maxCoarsePagesPerDocument;
            return (int) Math.min(Integer.MAX_VALUE, product);
        }

        /** Exact headings need a slightly wider in-book page window; natural-language queries retain the normal cap. */
        public int pagesPerDocument(boolean lexicalDominantQuery) {
            return lexicalDominantQuery ? lexicalDominantPagesPerDocument : maxPagesPerDocument;
        }

        /** Stable cache material for every setting that changes ranked output. */
        public String cacheIdentity() {
            return String.join(
                    ":",
                    String.valueOf(maxDocumentCandidates),
                    String.valueOf(maxPagesPerDocument),
                    String.valueOf(maxRerankDocuments),
                    String.valueOf(maxPageCandidates),
                    String.valueOf(maxCoarseDocumentCandidates),
                    String.valueOf(maxCoarsePagesPerDocument),
                    String.valueOf(coarseRrfK),
                    String.valueOf(pageTextChars),
                    String.valueOf(formulaTextChars),
                    String.valueOf(documentTextChars),
                    String.valueOf(lexicalDominantPagesPerDocument));
        }
    }

    private static int integerOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /** Reads boolean deployment switches without making invalid environment values change the safe default. */
    private static boolean booleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.strip())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.strip())) {
            return false;
        }
        return defaultValue;
    }
}
