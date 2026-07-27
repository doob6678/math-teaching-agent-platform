package com.doob.mathagent.teaching.mq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Covers the identity rule that separates idempotent creation from repeatable manual recovery. */
class MyBatisLectureTaskOutboxStoreTest {

    @Test
    void createEventIdentityIsStableAcrossConcurrentSubmissionAttempts() {
        String first = MyBatisLectureTaskOutboxStore.eventIdFor("task-1", "LECTURE_TASK_CREATED");
        String second = MyBatisLectureTaskOutboxStore.eventIdFor("task-1", "LECTURE_TASK_CREATED");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void everyResumeAttemptReceivesASeparateOutboxIdentity() {
        String first = MyBatisLectureTaskOutboxStore.eventIdFor("task-1", "LECTURE_TASK_RESUMED");
        String second = MyBatisLectureTaskOutboxStore.eventIdFor("task-1", "LECTURE_TASK_RESUMED");

        assertThat(first).isNotEqualTo(second);
    }
}
