package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingReviewPolicyTest {

    @Test
    void humanApprovalWaitsAfterAutomaticQualityGatePasses() {
        assertThat(TeachingReviewPolicy.HUMAN_APPROVAL.statusAfterQualityGate(true))
                .isEqualTo(TeachingTaskStatus.WAITING_REVIEW);
    }

    @Test
    void draftOnlyNeverPublishesEvenWhenAutomaticQualityGatePasses() {
        assertThat(TeachingReviewPolicy.DRAFT_ONLY.statusAfterQualityGate(true))
                .isEqualTo(TeachingTaskStatus.DRAFT_ONLY);
    }

    @Test
    void automaticPublicationCompletesOnlyWhenQualityGatePasses() {
        assertThat(TeachingReviewPolicy.AUTO_PUBLISH.statusAfterQualityGate(true))
                .isEqualTo(TeachingTaskStatus.COMPLETED);
        assertThat(TeachingReviewPolicy.AUTO_PUBLISH.statusAfterQualityGate(false))
                .isEqualTo(TeachingTaskStatus.FAILED);
    }
}
