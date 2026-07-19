package com.doob.mathagent.teaching;

import java.util.List;

/**
 * One printable knowledge-point unit resolved before writing a handout.
 *
 * <p>The pack binds the title, supporting RAG evidence, a worked example, and a variation together. This keeps the
 * document structure owned by verified retrieval instead of letting a free-form draft collapse unrelated questions
 * into one generic lesson section.</p>
 *
 * @param title concrete curriculum knowledge-point title
 * @param supportingEvidence textbook and authorized teacher-resource evidence for the title
 * @param workedExample real atomic question-bank item selected as the worked example
 * @param variation first real atomic question-bank item selected as a follow-up variation
 * @param additionalVariations remaining real variations for this exact knowledge point; they are rendered after the
 *                             first variation rather than silently discarded when the evidence bank has depth
 */
public record TeachingKnowledgePointPack(
        String title,
        List<TeachingEvidence> supportingEvidence,
        TeachingEvidence workedExample,
        TeachingEvidence variation,
        List<TeachingEvidence> additionalVariations) {

    /** Normalizes optional retrieval branches so every renderer can iterate without null checks. */
    public TeachingKnowledgePointPack {
        title = title == null ? "" : title.strip();
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        additionalVariations = additionalVariations == null ? List.of() : additionalVariations.stream()
                .filter(item -> item != null)
                .toList();
    }

    /** Keeps existing callers/tests source-compatible while allowing deeper retrieved question groups. */
    public TeachingKnowledgePointPack(
            String title,
            List<TeachingEvidence> supportingEvidence,
            TeachingEvidence workedExample,
            TeachingEvidence variation) {
        this(title, supportingEvidence, workedExample, variation, List.of());
    }

    /** Returns every verified variation in retrieval order, retaining the distinct first variation field. */
    public List<TeachingEvidence> variations() {
        if (variation == null) {
            return additionalVariations;
        }
        java.util.ArrayList<TeachingEvidence> values = new java.util.ArrayList<>();
        values.add(variation);
        values.addAll(additionalVariations);
        return List.copyOf(values);
    }
}
