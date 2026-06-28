package com.doob.mathagent.memory.service;

import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Student memory service for similar question reuse and stage timing.
 */
@Service
public class StudentMemoryReuseService {

    private static final double REUSE_THRESHOLD = 0.42;

    private final StudentMemoryStore store;
    private final Clock clock;

    /**
     * Creates a production memory service.
     *
     * @param store memory store
     */
    @Autowired
    public StudentMemoryReuseService(StudentMemoryStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates a testable memory service.
     *
     * @param store memory store
     * @param clock clock used for memory timestamps
     */
    public StudentMemoryReuseService(StudentMemoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Remembers a generated answer for future private or public reuse.
     *
     * @param request memory request
     * @return memory write response
     */
    public StudentMemoryResponse remember(StudentMemoryRequest request) {
        StageTimer timer = new StageTimer();
        StudentMemoryRequest normalized = request.normalize();
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
    public StudentMemoryResponse reuse(StudentMemoryRequest request) {
        StageTimer timer = new StageTimer();
        StudentMemoryRequest normalized = request.normalize();
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
        StudentMemoryEntry best = null;
        double bestScore = 0.0;
        for (StudentMemoryEntry candidate : store.candidates(normalized.tenantId(), normalized.studentId())) {
            double score = similarity(normalized, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        timer.mark("similarity_match");
        if (best != null && bestScore >= REUSE_THRESHOLD) {
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

    /**
     * Calculates similarity from question tokens and knowledge point match.
     *
     * @param request normalized memory request
     * @param candidate memory candidate
     * @return similarity from 0 to 1
     */
    private static double similarity(StudentMemoryRequest request, StudentMemoryEntry candidate) {
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
