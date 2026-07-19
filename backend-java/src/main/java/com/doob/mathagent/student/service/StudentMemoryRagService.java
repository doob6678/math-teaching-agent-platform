package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.vector.service.StudentMemorySearchHit;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StudentMemoryRagService {

    private static final int HARD_MAX_MEMORY_CHARS = 20_000;

    private final VectorIndexService vectorIndexService;
    private final int topK;
    private final int maxChars;

    public StudentMemoryRagService(
            VectorIndexService vectorIndexService,
            @Value("${math-agent.student.explanation.long-term-memory-top-k:5}") int topK,
            @Value("${math-agent.student.explanation.long-term-memory-max-chars:12000}") int maxChars) {
        this.vectorIndexService = vectorIndexService;
        this.topK = Math.max(1, Math.min(topK, 20));
        this.maxChars = Math.max(1_000, Math.min(maxChars, HARD_MAX_MEMORY_CHARS));
    }

    public List<String> retrieve(RequestSubject subject, String query) {
        String studentId = studentId(subject);
        if (studentId == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<String> selected = new ArrayList<>();
            int usedChars = 0;
            for (StudentMemorySearchHit hit : vectorIndexService.searchStudentMemories(
                    subject.tenantId(), studentId, query, topK)) {
                String content = normalize(hit.content());
                if (content.isBlank()) {
                    continue;
                }
                int available = maxChars - usedChars;
                if (available <= 0) {
                    break;
                }
                if (content.length() > available) {
                    content = content.substring(0, available);
                }
                selected.add(content);
                usedChars += content.length() + 1;
            }
            return List.copyOf(selected);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    public void index(RequestSubject subject, StudentExplanationResponse response) {
        String studentId = studentId(subject);
        if (studentId == null || response == null) {
            return;
        }
        String content = document(response);
        if (content.isBlank()) {
            return;
        }
        try {
            vectorIndexService.indexStudentMemory(subject.tenantId(), studentId, response.explanationId(), content);
        } catch (RuntimeException ignored) {
        }
    }

    private static String document(StudentExplanationResponse response) {
        StringBuilder content = new StringBuilder("题目：").append(normalize(response.questionText()));
        for (StudentExplanationResponse.ExplanationCard card : response.cards()) {
            append(content, card.title());
            append(content, card.summary());
            for (String item : card.items()) {
                append(content, item);
            }
        }
        return content.toString().strip();
    }

    private static void append(StringBuilder content, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            content.append('\n').append(normalized);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String studentId(RequestSubject subject) {
        if (subject == null || !"student".equals(subject.subjectType()) || subject.subjectId() == null) {
            return null;
        }
        return subject.subjectId();
    }
}
