package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ensures an unreviewed model answer cannot reach a visible canonical question. */
class QuestionPublicationGateTest {

    @Test
    void rejectsUnreviewedLunaAnswer() {
        PublicationDecision decision = QuestionPublicationGate.evaluate(new AnswerEvidence(
                "42", AnswerProvenance.MODEL_ASSISTED, false, "gpt-5.6-luna"));

        assertThat(decision.publishable()).isFalse();
        assertThat(decision.reason()).contains("human approval");
    }

    @Test
    void admitsOfficialOrHumanApprovedAnswersOnly() {
        assertThat(QuestionPublicationGate.evaluate(new AnswerEvidence(
                "42", AnswerProvenance.OFFICIAL, false, "official-source")).publishable()).isTrue();
        assertThat(QuestionPublicationGate.evaluate(new AnswerEvidence(
                "42", AnswerProvenance.MODEL_ASSISTED, true, "gpt-5.6-luna")).publishable()).isTrue();
    }
}
