package com.doob.mathagent.memory.service;

import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Student memory service for similar question reuse and stage timing.
 */
@Service
public class StudentMemoryReuseService {

    private static final double REUSE_THRESHOLD = 0.42;
    /** Keeps similarity work bounded while retaining the newest public and private memories. */
    private static final int MAX_REUSE_CANDIDATES = 500;
    private static final int MAX_SEMANTIC_CANDIDATES = 20;
    private static final Logger log = LoggerFactory.getLogger(StudentMemoryReuseService.class);

    private final StudentMemoryStore store;
    private final Clock clock;
    private final VectorIndexService vectorIndexService;
    private final boolean semanticReuseEnabled;
    private final boolean semanticReuseShadow;
    private final double semanticReuseThreshold;

    @Autowired
    public StudentMemoryReuseService(
            StudentMemoryStore store,
            VectorIndexService vectorIndexService,
            @Value("${math-agent.student.memory.semantic-reuse-enabled:false}") boolean semanticReuseEnabled,
            @Value("${math-agent.student.memory.semantic-reuse-shadow:true}") boolean semanticReuseShadow,
            @Value("${math-agent.student.memory.semantic-reuse-threshold:0.78}") double semanticReuseThreshold) {
        this(store, Clock.systemUTC(), vectorIndexService, semanticReuseEnabled, semanticReuseShadow, semanticReuseThreshold);
    }

    public StudentMemoryReuseService(StudentMemoryStore store) {
        this(store, Clock.systemUTC(), null, false, true, 0.78d);
    }

    public StudentMemoryReuseService(StudentMemoryStore store, Clock clock) {
        this(store, clock, null, false, true, 0.78d);
    }

    public StudentMemoryReuseService(
            StudentMemoryStore store,
            Clock clock,
            VectorIndexService vectorIndexService,
            boolean semanticReuseEnabled,
            boolean semanticReuseShadow,
            double semanticReuseThreshold) {
        this.store = store;
        this.clock = clock;
        this.vectorIndexService = vectorIndexService;
        this.semanticReuseEnabled = semanticReuseEnabled;
        this.semanticReuseShadow = semanticReuseShadow;
        this.semanticReuseThreshold = Double.isFinite(semanticReuseThreshold)
                ? Math.max(0.0d, Math.min(1.0d, semanticReuseThreshold)) : 0.78d;
    }

    /**
     * Creates a production memory service.
     *
     * @param store memory store
     */
    /* legacy constructor retained above */
    /*
    @Autowired
    public StudentMemoryReuseService(StudentMemoryStore store) {
        this(store, Clock.systemUTC());
    }
    */

    /**
     * Creates a testable memory service.
     *
     * @param store memory store
     * @param clock clock used for memory timestamps
     */
    /* legacy constructor retained above */
    /*
    public StudentMemoryReuseService(StudentMemoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }
    */

    /**
     * Remembers a generated answer for future private or public reuse.
     *
     * @param request memory request
     * @return memory write response
     */
    public StudentMemoryResponse remember(StudentMemoryCommand request) {
        StageTimer timer = new StageTimer();
        StudentMemoryCommand normalized = request.normalize();
        timer.mark("normalize");
        if (!normalized.rememberable()) {
            return new StudentMemoryResponse(
                    false,
                    null,
                    normalized.memoryScope(),
                    null,
                    0.0,
                    "Memory request is not rememberable",
                    timer.finish("write_decision"));
        }
        StudentMemoryEntry entry = new StudentMemoryEntry(
                UUID.randomUUID().toString(),
                normalized.tenantId(),
                normalized.studentId(),
                normalizeScope(normalized.memoryScope(), normalized.viewerRole()),
                normalized.knowledgePointName(),
                normalized.questionText(),
                normalized.answerText(),
                "active",
                Instant.now(clock));
        StudentMemoryEntry saved = store.save(entry);
        return new StudentMemoryResponse(
                false,
                saved.memoryId(),
                saved.memoryScope(),
                saved.answerText(),
                1.0,
                "Memory stored",
                timer.finish("write_memory"));
    }

    /**
     * Finds a reusable memory answer for a similar question.
     *
     * @param request memory reuse request
     * @return reuse decision response
     */
    public StudentMemoryResponse reuse(StudentMemoryCommand request) {
        StageTimer timer = new StageTimer();
        StudentMemoryCommand normalized = request.normalize();
        timer.mark("normalize");
        if (normalized.bypassReuse()) {
            return new StudentMemoryResponse(
                    false,
                    null,
                    null,
                    null,
                    0.0,
                    "Reuse bypass requested",
                    timer.finish("reuse_decision"));
        }
        List<StudentMemoryEntry> candidates = store.candidates(
                normalized.tenantId(), normalized.studentId(), MAX_REUSE_CANDIDATES);
        List<StudentMemoryEntry> semanticCandidates = candidates.stream()
                .sorted(java.util.Comparator.comparingDouble((StudentMemoryEntry candidate) -> similarity(normalized, candidate)).reversed())
                .limit(MAX_SEMANTIC_CANDIDATES)
                .toList();
        List<Double> semanticScores = semanticScores(normalized, semanticCandidates);
        java.util.Map<String, Double> semanticScoreByMemoryId = new java.util.HashMap<>();
        for (int index = 0; index < semanticCandidates.size(); index += 1) {
            semanticScoreByMemoryId.put(semanticCandidates.get(index).memoryId(), semanticScores.get(index));
        }
        StudentMemoryEntry best = null;
        double bestScore = 0.0;
        for (StudentMemoryEntry candidate : candidates) {
            double lexicalScore = similarity(normalized, candidate);
            double semanticScore = semanticScoreByMemoryId.getOrDefault(candidate.memoryId(), 0.0d);
            double score = semanticReuseEnabled && semanticScore > 0.0d ? semanticScore : lexicalScore;
            if (!mathSafetyGate(normalized.questionText(), candidate.questionText())) {
                if (semanticScore > 0.0d) {
                    log.info("student_memory_semantic_reuse_rejected memoryId={} lexicalScore={} semanticScore={} reason=math_parameter_conflict",
                            candidate.memoryId(), lexicalScore, semanticScore);
                }
                continue;
            }
            if (semanticReuseShadow && semanticScore > 0.0d) {
                log.info("student_memory_semantic_reuse_shadow memoryId={} lexicalScore={} semanticScore={} enabled={}",
                        candidate.memoryId(), lexicalScore, semanticScore, semanticReuseEnabled);
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        timer.mark("similarity_match");
        double threshold = semanticReuseEnabled ? semanticReuseThreshold : REUSE_THRESHOLD;
        if (best != null && bestScore >= threshold) {
            return new StudentMemoryResponse(
                    true,
                    best.memoryId(),
                    best.memoryScope(),
                    best.answerText(),
                    bestScore,
                    "Reusable memory matched",
                    timer.finish("reuse_decision"));
        }
        return new StudentMemoryResponse(
                false,
                null,
                null,
                null,
                bestScore,
                "No reusable memory matched",
                timer.finish("reuse_decision"));
    }

    private List<Double> semanticScores(StudentMemoryCommand request, List<StudentMemoryEntry> candidates) {
        if (vectorIndexService == null || candidates.isEmpty() || (!semanticReuseEnabled && !semanticReuseShadow)) {
            return java.util.Collections.nCopies(candidates.size(), 0.0d);
        }
        try {
            List<Double> scores = vectorIndexService.semanticSimilarity(
                    request.questionText(), candidates.stream().map(StudentMemoryEntry::questionText).toList());
            return scores.size() == candidates.size() ? scores : java.util.Collections.nCopies(candidates.size(), 0.0d);
        } catch (RuntimeException ignored) {
            return java.util.Collections.nCopies(candidates.size(), 0.0d);
        }
    }

    /** Rejects obvious parameter/formula mismatches before a semantic score can authorize reuse. */
    private static boolean mathSafetyGate(String question, String candidate) {
        Set<String> leftNumbers = numbers(question);
        Set<String> rightNumbers = numbers(candidate);
        if (!leftNumbers.isEmpty() && !rightNumbers.isEmpty() && !leftNumbers.equals(rightNumbers)) {
            return false;
        }
        boolean leftQuadratic = containsAny(question, "x^2", "x²", "二次函数", "二次方程");
        boolean rightQuadratic = containsAny(candidate, "x^2", "x²", "二次函数", "二次方程");
        boolean leftCubic = containsAny(question, "x^3", "x³", "三次函数", "三次方程");
        boolean rightCubic = containsAny(candidate, "x^3", "x³", "三次函数", "三次方程");
        return !(leftQuadratic && rightCubic) && !(leftCubic && rightQuadratic);
    }

    private static Set<String> numbers(String value) {
        Set<String> values = new HashSet<>();
        if (value == null) return values;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(value);
        while (matcher.find()) values.add(matcher.group());
        return values;
    }

    private static boolean containsAny(String value, String... terms) {
        String normalized = value == null ? "" : value.toLowerCase();
        for (String term : terms) if (normalized.contains(term.toLowerCase())) return true;
        return false;
    }

    /**
     * Calculates similarity from question tokens and knowledge point match.
     *
     * @param request normalized memory request
     * @param candidate memory candidate
     * @return similarity from 0 to 1
     */
    private static double similarity(StudentMemoryCommand request, StudentMemoryEntry candidate) {
        Set<String> left = tokens(request.questionText());
        Set<String> right = tokens(candidate.questionText());
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        double textScore = (double) intersection.size() / (double) union.size();
        boolean sameKnowledge = !request.knowledgePointName().isBlank()
                && request.knowledgePointName().equals(candidate.knowledgePointName());
        return sameKnowledge ? Math.min(1.0, textScore + 0.25) : textScore;
    }

    /**
     * Builds coarse tokens for Chinese/math question similarity without external dependencies.
     *
     * @param text question text
     * @return token set
     */
    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        String compact = text.toLowerCase().replaceAll("\\s+", "");
        for (int index = 0; index < compact.length(); index++) {
            tokens.add(String.valueOf(compact.charAt(index)));
            if (index + 1 < compact.length()) {
                tokens.add(compact.substring(index, index + 2));
            }
        }
        return tokens;
    }

    /**
     * Normalizes unsupported scopes and unprivileged public writes to private for safety.
     *
     * @param scope requested scope
     * @return private or public
     */
    private static String normalizeScope(String scope, String viewerRole) {
        boolean privilegedWriter = "teacher".equals(viewerRole) || "admin".equals(viewerRole);
        return privilegedWriter && "public".equals(scope) ? "public" : "private";
    }

    /**
     * Small timing helper for memory pipeline stages.
     */
    private static final class StageTimer {

        private final List<StudentMemoryResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        /**
         * Records elapsed time for a stage and resets the timer checkpoint.
         *
         * @param stage stage code
         */
        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new StudentMemoryResponse.StageTiming(stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        /**
         * Records the final stage and returns all timings.
         *
         * @param stage final stage code
         * @return timing list
         */
        List<StudentMemoryResponse.StageTiming> finish(String stage) {
            mark(stage);
            return List.copyOf(timings);
        }
    }
}
