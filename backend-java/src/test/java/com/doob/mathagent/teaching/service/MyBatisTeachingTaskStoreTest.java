package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import com.doob.mathagent.teaching.mq.LectureTaskLease;
import com.doob.mathagent.teaching.mq.LectureTaskLeaseStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MyBatisTeachingTaskStoreTest {

    @Test
    void persistsTerminalOutputContractFailureAsFailedApiSnapshotWithoutRetry() throws Exception {
        AtomicReference<Object[]> capturedArguments = new AtomicReference<>();
        TeachingTaskMapper mapper = (TeachingTaskMapper) Proxy.newProxyInstance(
                TeachingTaskMapper.class.getClassLoader(),
                new Class<?>[] {TeachingTaskMapper.class},
                (proxy, method, arguments) -> {
                    if ("selectById".equals(method.getName())) {
                        return null;
                    }
                    if ("failOwnedLectureTask".equals(method.getName())) {
                        capturedArguments.set(arguments);
                        return 1;
                    }
                    throw new AssertionError("Unexpected mapper call: " + method.getName());
                });
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MyBatisTeachingTaskStore store = new MyBatisTeachingTaskStore(mapper, objectMapper);
        TeachingTaskResponse failed = runningTask().withReviewStatus(
                TeachingTaskStatus.FAILED, "422 HANDOUT_OUTPUT_CONTRACT_FAILURE");

        LectureTaskLeaseStore.FailureOutcome outcome = store.failOwned(
                new LectureTaskLease("task-failed", "lease-contract", "worker-a", 1, Instant.now()),
                failed,
                "422 HANDOUT_OUTPUT_CONTRACT_FAILURE",
                0);

        assertThat(outcome).isEqualTo(LectureTaskLeaseStore.FailureOutcome.TERMINAL_FAILURE);
        Object[] arguments = capturedArguments.get();
        assertThat(arguments).isNotNull();
        assertThat(arguments[3]).isEqualTo("FAILED");
        assertThat(arguments[5]).isInstanceOf(Instant.class);
        TeachingTaskResponse persisted = objectMapper.readValue((String) arguments[2], TeachingTaskResponse.class);
        assertThat(persisted.status()).isEqualTo(TeachingTaskStatus.FAILED);
        assertThat(persisted.errorMessage()).isEqualTo("422 HANDOUT_OUTPUT_CONTRACT_FAILURE");
    }

    /** A task that exhausted automatic retries must receive a fresh lease budget after a deliberate user resume. */
    @Test
    void preparesTerminalTaskForManualResumeWithFreshWorkerState() throws Exception {
        AtomicReference<Object[]> capturedArguments = new AtomicReference<>();
        TeachingTaskMapper mapper = (TeachingTaskMapper) Proxy.newProxyInstance(
                TeachingTaskMapper.class.getClassLoader(),
                new Class<?>[] {TeachingTaskMapper.class},
                (proxy, method, arguments) -> {
                    if ("selectById".equals(method.getName())) {
                        return null;
                    }
                    if ("prepareLectureTaskForResume".equals(method.getName())) {
                        capturedArguments.set(arguments);
                        return 1;
                    }
                    throw new AssertionError("Unexpected mapper call: " + method.getName());
                });
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MyBatisTeachingTaskStore store = new MyBatisTeachingTaskStore(mapper, objectMapper);
        TeachingTaskResponse running = runningTask();

        TeachingTaskResponse result = store.prepareForResume(
                "tenant-a:teacher:teacher-1", "owner:req-1", running);

        assertThat(result).isSameAs(running);
        Object[] arguments = capturedArguments.get();
        assertThat(arguments).isNotNull();
        assertThat(arguments[0]).isEqualTo("task-failed");
        assertThat(arguments[1]).isEqualTo("tenant-a:teacher:teacher-1");
        assertThat(arguments[3]).isInstanceOf(Instant.class);
        TeachingTaskResponse persisted = objectMapper.readValue((String) arguments[2], TeachingTaskResponse.class);
        assertThat(persisted.status()).isEqualTo(TeachingTaskStatus.RUNNING);
        assertThat(persisted.taskId()).isEqualTo("task-failed");

        String resumeSql;
        try (InputStream mapperXml = getClass().getResourceAsStream("/mapper/TeachingTaskMapper.xml")) {
            assertThat(mapperXml).isNotNull();
            resumeSql = new String(mapperXml.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(resumeSql).contains(
                "<update id=\"prepareLectureTaskForResume\">",
                "status = 'RETRYING'",
                "retry_count = 0",
                "lease_owner = NULL",
                "lease_token = NULL",
                "lease_expire_at = NULL",
                "last_error = NULL",
                "finished_at = NULL",
                "owner_key = #{ownerKey}",
                "status IN ('FAILED', 'RETRYING', 'COMPLETED')",
                "status = 'RUNNING' AND (lease_expire_at IS NULL OR lease_expire_at &lt; #{updatedAt})");
    }

    /**
     * broker canonical 精读写回的题图绑定必须跨编排器进度保存存活：椭圆事故中精读之后的每次
     * saveOwnedRunning 都用内存快照覆盖了账本，导出反查失败 fail-closed 丢掉了全部图片。
     */
    @Test
    void progressSavesCarryDurableImageBindingsIntoStaleMemorySnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<Map<String, String>> bindings = List.of(Map.of(
                "markdownLine", "![source-image:e3bf1957645d-image-001](figures/q-016-01.png)",
                "logicalPath", "figures/q-016-01.png"));
        // 持久化行同时携带 broker 后写的 assetIds：进度快照必须两者都结转，不得只保 imageRefs。
        TeachingEvidence durableRow = canonicalEvidence("doc-16", "16", bindings);
        TeachingTaskResponse persisted = runningTask().withEvidence(List.of(new TeachingEvidence(
                durableRow.sourceScope(), durableRow.sourceTitle(), durableRow.chunkId(), durableRow.pageNo(),
                durableRow.snippet(), durableRow.imagePath(), durableRow.imageDescription(),
                durableRow.sourceDocumentId(), durableRow.sourceType(), durableRow.sourceUrl(),
                durableRow.sourcePath(), List.of("asset-77"), durableRow.canonicalQuestionNumber(),
                durableRow.imageRefs())));
        TeachingTaskEntity existing = new TeachingTaskEntity();
        existing.setTaskId("task-failed");
        existing.setResponseJson(objectMapper.writeValueAsString(persisted));
        AtomicReference<Object[]> capturedArguments = new AtomicReference<>();
        TeachingTaskMapper mapper = (TeachingTaskMapper) Proxy.newProxyInstance(
                TeachingTaskMapper.class.getClassLoader(),
                new Class<?>[] {TeachingTaskMapper.class},
                (proxy, method, arguments) -> {
                    if ("selectById".equals(method.getName())) {
                        return existing;
                    }
                    if ("saveOwnedRunningLectureTask".equals(method.getName())) {
                        capturedArguments.set(arguments);
                        return 1;
                    }
                    throw new AssertionError("Unexpected mapper call: " + method.getName());
                });
        MyBatisTeachingTaskStore store = new MyBatisTeachingTaskStore(mapper, objectMapper);
        // 编排器内存快照：同一证据行但 imageRefs 为空（精读前构建的旧视图）。
        TeachingTaskResponse stale = runningTask().withEvidence(
                List.of(canonicalEvidence("doc-16", "16", List.of())));

        boolean saved = store.saveOwnedRunning(
                new LectureTaskLease("task-failed", "lease-bind", "worker-a", 1, Instant.now()), stale);

        assertThat(saved).isTrue();
        TeachingTaskResponse written = objectMapper.readValue(
                (String) capturedArguments.get()[2], TeachingTaskResponse.class);
        assertThat(written.evidence().get(0).imageRefs()).isEqualTo(bindings);
        assertThat(written.evidence().get(0).assetIds()).containsExactly("asset-77");
    }

    private static TeachingEvidence canonicalEvidence(
            String documentId, String questionNumber, List<Map<String, String>> imageRefs) {
        return new TeachingEvidence("CANONICAL_MATH_PAPER", "canonical paper", "chunk-" + documentId, 0,
                "snippet", "", "", documentId, "gaokao", "", "", List.of(), questionNumber, imageRefs);
    }

    private static TeachingTaskResponse runningTask() {
        return new TeachingTaskResponse(
                "task-failed", "req-1", "tenant-a", "teacher", "teacher-1", null,
                TeachingTaskStatus.RUNNING, "求三角形的解", "正弦定理分类讨论", null,
                List.of(), List.of(), List.of(), List.of(), "", "", "", "", List.of(), null,
                List.of(), null, null, null, null, null);
    }
}
