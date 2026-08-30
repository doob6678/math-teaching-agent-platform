package com.doob.mathagent.teacher.search;

import org.springframework.core.env.Environment;

/**
 * Teacher resource retrieval runtime configuration.
 *
 * <p>Keep these values outside {@code TeacherResourceBlockSearchService} so the production search path no longer
 * buries transport budgets, truncation ceilings, and query-focus limits inside one large class. The retriever still
 * stays semantic-first; these properties only bound payload size and candidate windows so the real rerank path can run
 * predictably on the local worker and audit storage.</p>
 *
 * @param defaultLimit fallback response size when callers do not provide a positive limit
 * @param maxLimit hard upper bound for one search response
 * @param queryFocus semantic query focus configuration
 * @param runtime semantic rerank and audit payload budgets
 */
public record TeacherResourceSearchProperties(
        int defaultLimit,
        int maxLimit,
        QueryFocusBudget queryFocus,
        SearchRuntimeBudget runtime,
        FileCandidateFusion candidateFusion) {

    /**
     * Returns normalized defaults for non-Spring and test constructors.
     */
    public static TeacherResourceSearchProperties defaults() {
        return new TeacherResourceSearchProperties(
                10,
                20,
                QueryFocusBudget.defaults(),
                SearchRuntimeBudget.defaults(),
                FileCandidateFusion.defaults());
    }

    /**
     * Loads properties from Spring environment while preserving project-owned defaults.
     */
    public static TeacherResourceSearchProperties fromSpringEnvironment(Environment environment) {
        TeacherResourceSearchProperties defaults = defaults();
        QueryFocusBudget defaultFocus = defaults.queryFocus();
        SearchRuntimeBudget defaultRuntime = defaults.runtime();
        return new TeacherResourceSearchProperties(
                integerOrDefault(environment.getProperty("math-agent.teacher.search.default-limit"), defaults.defaultLimit()),
                integerOrDefault(environment.getProperty("math-agent.teacher.search.max-limit"), defaults.maxLimit()),
                new QueryFocusBudget(
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.query-focus.max-semantic-query-chars"),
                                defaultFocus.maxSemanticQueryChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.query-focus.max-clauses"),
                                defaultFocus.maxClauses()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.query-focus.max-graph-tags"),
                                defaultFocus.maxGraphTags())),
                new SearchRuntimeBudget(
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-retrieval-mode-length"),
                                defaultRuntime.maxRetrievalModeLength()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.snippet-radius"),
                                defaultRuntime.snippetRadius()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.evidence-window-radius"),
                                defaultRuntime.evidenceWindowRadius()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-search-terms"),
                                defaultRuntime.maxSearchTerms()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.title-chars"),
                                defaultRuntime.titleChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.role-chars"),
                                defaultRuntime.roleChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.heading-chars"),
                                defaultRuntime.headingChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.source-path-chars"),
                                defaultRuntime.sourcePathChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.graph-tags-chars"),
                                defaultRuntime.graphTagsChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.image-refs-chars"),
                                defaultRuntime.imageRefsChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.evidence-block-ids-chars"),
                                defaultRuntime.evidenceBlockIdsChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.document-evidence-chars"),
                                defaultRuntime.documentEvidenceChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.block-evidence-chars"),
                                defaultRuntime.blockEvidenceChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.merge-evidence-chars"),
                                defaultRuntime.mergeEvidenceChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.document-digest-chars"),
                                defaultRuntime.documentDigestChars()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-document-rerank-candidates"),
                                defaultRuntime.maxDocumentRerankCandidates()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-vector-candidates"),
                                defaultRuntime.maxVectorCandidates()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-block-rerank-candidates"),
                                defaultRuntime.maxBlockRerankCandidates()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-blocks-per-document-for-stage-two"),
                                defaultRuntime.maxBlocksPerDocumentForStageTwo()),
                        booleanOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.lexical-rescue-enabled"),
                                defaultRuntime.lexicalRescueEnabled()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.max-lexical-rescue-blocks-per-document"),
                                defaultRuntime.maxLexicalRescueBlocksPerDocument()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.minimum-rerank-score"),
                                defaultRuntime.minimumRerankScore()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.low-confidence-score"),
                                defaultRuntime.lowConfidenceScore()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.runtime.minimum-rerank-margin"),
                                defaultRuntime.minimumRerankMargin())),
                new FileCandidateFusion(
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.rrf-k"),
                                defaults.candidateFusion().rrfK()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.vector-weight"),
                                defaults.candidateFusion().vectorWeight()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.lexical-weight"),
                                defaults.candidateFusion().lexicalWeight()),
                        doubleOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.tag-weight"),
                                defaults.candidateFusion().tagWeight()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.route-candidate-limit"),
                                defaults.candidateFusion().routeCandidateLimit()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.vector-quality-limit"),
                                defaults.candidateFusion().vectorQualityLimit()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.lexical-quality-limit"),
                                defaults.candidateFusion().lexicalQualityLimit()),
                        integerOrDefault(
                                environment.getProperty("math-agent.teacher.search.candidate-fusion.tag-quality-limit"),
                                defaults.candidateFusion().tagQualityLimit())));
    }

    public TeacherResourceSearchProperties {
        queryFocus = queryFocus == null ? QueryFocusBudget.defaults() : queryFocus;
        runtime = runtime == null ? SearchRuntimeBudget.defaults() : runtime;
        candidateFusion = candidateFusion == null ? FileCandidateFusion.defaults() : candidateFusion;
        defaultLimit = Math.max(1, defaultLimit);
        maxLimit = Math.max(defaultLimit, maxLimit);
    }

    /**
     * Query-focus parameters. These are not ranking weights; they only decide how much orchestration noise is removed
     * before semantic retrieval.
     */
    public record QueryFocusBudget(
            int maxSemanticQueryChars,
            int maxClauses,
            int maxGraphTags) {

        public static QueryFocusBudget defaults() {
            return new QueryFocusBudget(160, 3, 4);
        }

        public QueryFocusBudget {
            maxSemanticQueryChars = Math.max(32, maxSemanticQueryChars);
            maxClauses = Math.max(1, maxClauses);
            maxGraphTags = Math.max(0, maxGraphTags);
        }
    }

    /**
     * Semantic rerank and audit payload budgets. These cap I/O, not semantic score composition.
     */
    public record SearchRuntimeBudget(
            int maxRetrievalModeLength,
            int snippetRadius,
            int evidenceWindowRadius,
            int maxSearchTerms,
            int titleChars,
            int roleChars,
            int headingChars,
            int sourcePathChars,
            int graphTagsChars,
            int imageRefsChars,
            int evidenceBlockIdsChars,
            int documentEvidenceChars,
            int blockEvidenceChars,
            int mergeEvidenceChars,
            int documentDigestChars,
            int maxDocumentRerankCandidates,
            int maxVectorCandidates,
            int maxBlockRerankCandidates,
            int maxBlocksPerDocumentForStageTwo,
            boolean lexicalRescueEnabled,
            int maxLexicalRescueBlocksPerDocument,
            double minimumRerankScore,
            double lowConfidenceScore,
            double minimumRerankMargin) {

        public static SearchRuntimeBudget defaults() {
            return new SearchRuntimeBudget(
                    64,
                    80,
                    16,
                    32,
                    120,
                    48,
                    120,
                    220,
                    180,
                    180,
                    120,
                    520,
                    900,
                    760,
                    2600,
                    12,
                    96,
                    36,
                    3,
                    false,
                    12,
                    -6.60d,
                    -6.00d,
                    0.15d);
        }

        public SearchRuntimeBudget {
            maxRetrievalModeLength = Math.max(16, maxRetrievalModeLength);
            snippetRadius = Math.max(0, snippetRadius);
            evidenceWindowRadius = Math.max(0, evidenceWindowRadius);
            maxSearchTerms = Math.max(1, maxSearchTerms);
            titleChars = Math.max(16, titleChars);
            roleChars = Math.max(8, roleChars);
            headingChars = Math.max(16, headingChars);
            sourcePathChars = Math.max(16, sourcePathChars);
            graphTagsChars = Math.max(16, graphTagsChars);
            imageRefsChars = Math.max(0, imageRefsChars);
            evidenceBlockIdsChars = Math.max(0, evidenceBlockIdsChars);
            documentEvidenceChars = Math.max(32, documentEvidenceChars);
            blockEvidenceChars = Math.max(32, blockEvidenceChars);
            mergeEvidenceChars = Math.max(32, mergeEvidenceChars);
            documentDigestChars = Math.max(documentEvidenceChars, documentDigestChars);
            maxDocumentRerankCandidates = Math.max(1, maxDocumentRerankCandidates);
            maxVectorCandidates = Math.max(1, maxVectorCandidates);
            maxBlockRerankCandidates = Math.min(12, Math.max(1, maxBlockRerankCandidates));
            maxBlocksPerDocumentForStageTwo = Math.max(1, maxBlocksPerDocumentForStageTwo);
            maxLexicalRescueBlocksPerDocument = Math.max(1, maxLexicalRescueBlocksPerDocument);
            minimumRerankScore = Double.isFinite(minimumRerankScore) ? minimumRerankScore : -6.60d;
            lowConfidenceScore = Double.isFinite(lowConfidenceScore) ? lowConfidenceScore : -6.00d;
            minimumRerankMargin = Math.max(0.0d, Double.isFinite(minimumRerankMargin) ? minimumRerankMargin : 0.15d);
        }

        public int vectorCandidateLimit(int requestedLimit, int candidateDocumentCount) {
            int requestedWindow = Math.max(1, requestedLimit) * Math.max(1, candidateDocumentCount);
            return Math.max(1, Math.min(maxVectorCandidates, requestedWindow));
        }

        public int documentRerankCandidateLimit(int visibleDocumentCount) {
            return Math.max(1, Math.min(maxDocumentRerankCandidates, Math.max(1, visibleDocumentCount)));
        }

        public int blockRerankCandidateLimit(int rankedDocumentCount) {
            return Math.max(1, Math.min(
                    maxBlockRerankCandidates,
                    Math.max(1, rankedDocumentCount) * Math.max(1, maxBlocksPerDocumentForStageTwo)));
        }
    }

    /** FILE-level route fusion parameters. RRF combines ranks only after each route deduplicates physical files. */
    public record FileCandidateFusion(
            int rrfK,
            double vectorWeight,
            double lexicalWeight,
            double tagWeight,
            int routeCandidateLimit,
            int vectorQualityLimit,
            int lexicalQualityLimit,
            int tagQualityLimit) {

        public FileCandidateFusion(
                int rrfK,
                double vectorWeight,
                double lexicalWeight,
                double tagWeight,
                int routeCandidateLimit) {
            this(rrfK, vectorWeight, lexicalWeight, tagWeight, routeCandidateLimit, 32, 32, 32);
        }

        public static FileCandidateFusion defaults() {
            // Lexical recall remains observable and operator-configurable, but generic Chinese terms currently create
            // broad FILE candidates; keep semantic and graph evidence authoritative in the production default.
            return new FileCandidateFusion(60, 1.0d, 0.0d, 1.0d, 96, 32, 32, 32);
        }

        public FileCandidateFusion {
            rrfK = Math.max(1, rrfK);
            vectorWeight = Math.max(0.0d, vectorWeight);
            lexicalWeight = Math.max(0.0d, lexicalWeight);
            tagWeight = Math.max(0.0d, tagWeight);
            routeCandidateLimit = Math.max(12, Math.min(256, routeCandidateLimit));
            vectorQualityLimit = boundedQualityLimit(vectorQualityLimit, routeCandidateLimit);
            lexicalQualityLimit = boundedQualityLimit(lexicalQualityLimit, routeCandidateLimit);
            tagQualityLimit = boundedQualityLimit(tagQualityLimit, routeCandidateLimit);
        }

        private static int boundedQualityLimit(int value, int routeLimit) {
            return Math.max(1, Math.min(routeLimit, value));
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

    private static double doubleOrDefault(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /** Reads a boolean environment override while retaining the safe default on malformed operator input. */
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
