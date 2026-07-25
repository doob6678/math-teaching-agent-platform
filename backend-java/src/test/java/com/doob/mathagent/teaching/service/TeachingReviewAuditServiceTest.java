package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingReviewPolicy;
import com.doob.mathagent.teaching.vo.TeachingReviewAuditResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingReviewAuditServiceTest {

    @Test
    void recordsImmutableReviewSnapshotWithoutPersistingDraftContent() {
        InMemoryTeachingReviewAuditStore store = new InMemoryTeachingReviewAuditStore();
        TeachingReviewAuditService service = new TeachingReviewAuditService(
                store,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC));

        TeachingReviewAuditResponse audit = service.record(
                "task-1",
                new TeachingRequestContext("tenant-1", "teacher", "teacher-1", "device-1"),
                TeachingReviewPolicy.HUMAN_APPROVAL,
                "APPROVE",
                "verified citations",
                "draft-sha256",
                "PASSED",
                "teacher-sha256",
                "student-sha256",
                "lecture-sha256",
                "COMPLETED");

        assertThat(store.list("task-1")).containsExactly(audit);
        assertThat(audit.createdAt()).isEqualTo(Instant.parse("2026-07-24T00:00:00Z"));
        assertThat(audit.commonDraftHash()).isEqualTo("draft-sha256");
        assertThat(audit.reasonText()).doesNotContain("draft content");
    }

    @Test
    void normalizesAuditDecisionBeforeWritingIt() {
        TeachingReviewAuditService service = new TeachingReviewAuditService(
                new InMemoryTeachingReviewAuditStore(), Clock.systemUTC());

        TeachingReviewAuditResponse audit = service.record(
                "task-1", new TeachingRequestContext("tenant-1", "teacher", "teacher-1", "device-1"),
                TeachingReviewPolicy.DRAFT_ONLY, " approve ", "", "draft", "passed", "", "", "", "draft_only");

        assertThat(audit.decisionCode()).isEqualTo("APPROVE");
        assertThat(audit.qualityStatus()).isEqualTo("PASSED");
        assertThat(audit.publishedStatus()).isEqualTo("DRAFT_ONLY");
    }
}
