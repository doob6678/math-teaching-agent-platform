package com.doob.mathagent.ingestion;

/**
 * Enforces the answer boundary shared by indexing and every handout exporter. A missing solution is allowed, but a
 * missing or unreviewed final answer is not.
 */
public final class QuestionPublicationGate {
    private QuestionPublicationGate() { }

    /** Evaluates evidence without inferring approval from a model name or a non-empty solution. */
    public static PublicationDecision evaluate(AnswerEvidence evidence) {
        if (evidence == null || evidence.answer() == null || evidence.answer().isBlank()) {
            return new PublicationDecision(false, "A published question requires a final answer");
        }
        if (evidence.provenance() == AnswerProvenance.OFFICIAL) {
            return new PublicationDecision(true, "Official answer is eligible for review/publication");
        }
        if (evidence.humanApproved() && (evidence.provenance() == AnswerProvenance.MODEL_ASSISTED
                || evidence.provenance() == AnswerProvenance.HUMAN_APPROVED_MODEL_ASSISTED
                || evidence.provenance() == AnswerProvenance.MANUAL)) {
            return new PublicationDecision(true, "Human-approved answer is eligible for publication");
        }
        return new PublicationDecision(false, "Model-assisted answer requires human approval before publication");
    }
}
