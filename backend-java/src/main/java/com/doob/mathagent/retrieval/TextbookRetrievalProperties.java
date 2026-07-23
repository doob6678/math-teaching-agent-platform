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
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.query-focus.max-graph-tags"), focus.maxGraphTags())),
                new RerankBudget(
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-document-candidates"), budget.maxDocumentCandidates()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-pages-per-document"), budget.maxPagesPerDocument()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-rerank-documents"), budget.maxRerankDocuments()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.max-page-candidates"), budget.maxPageCandidates()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.page-text-chars"), budget.pageTextChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.formula-text-chars"), budget.formulaTextChars()),
                        integerOrDefault(environment.getProperty("math-agent.textbook.retrieval.rerank.document-text-chars"), budget.documentTextChars())));
    }

    /** Limits for removing routing noise before semantic retrieval. */
    public record QueryFocus(int maxQueryChars, int maxClauses, int maxGraphTags) {
        public static QueryFocus defaults() {
            return new QueryFocus(120, 2, 4);
        }

        public QueryFocus {
            maxQueryChars = Math.max(32, maxQueryChars);
            maxClauses = Math.max(1, maxClauses);
            maxGraphTags = Math.max(0, maxGraphTags);
        }
    }

    /** Bounded payload and candidate windows for the real local rerank endpoint. */
    public record RerankBudget(
            int maxDocumentCandidates,
            int maxPagesPerDocument,
            int maxRerankDocuments,
            int maxPageCandidates,
            int pageTextChars,
            int formulaTextChars,
            int documentTextChars) {
        public static RerankBudget defaults() {
            return new RerankBudget(
                    DEFAULT_MAX_DOCUMENT_CANDIDATES,
                    DEFAULT_MAX_PAGES_PER_DOCUMENT,
                    DEFAULT_MAX_RERANK_DOCUMENTS,
                    DEFAULT_MAX_PAGE_CANDIDATES,
                    120,
                    40,
                    320);
        }

        public RerankBudget {
            maxDocumentCandidates = Math.max(1, maxDocumentCandidates);
            maxPagesPerDocument = Math.max(1, maxPagesPerDocument);
            maxRerankDocuments = Math.max(1, Math.min(maxRerankDocuments, maxDocumentCandidates));
            maxPageCandidates = Math.max(1, maxPageCandidates);
            pageTextChars = Math.max(32, pageTextChars);
            formulaTextChars = Math.max(0, formulaTextChars);
            documentTextChars = Math.max(pageTextChars, documentTextChars);
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

        /** Stable cache material for every setting that changes ranked output. */
        public String cacheIdentity() {
            return String.join(
                    ":",
                    String.valueOf(maxDocumentCandidates),
                    String.valueOf(maxPagesPerDocument),
                    String.valueOf(maxRerankDocuments),
                    String.valueOf(maxPageCandidates),
                    String.valueOf(pageTextChars),
                    String.valueOf(formulaTextChars),
                    String.valueOf(documentTextChars));
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
}
