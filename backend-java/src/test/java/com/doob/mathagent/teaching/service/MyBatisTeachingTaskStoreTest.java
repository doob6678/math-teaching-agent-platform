package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MyBatisTeachingTaskStoreTest {

    /** A task that exhausted automatic retries must receive a fresh lease budget after a deliberate user resume. */
    @Test
    void preparesTerminalTaskForManualResumeWithFreshWorkerState() throws Exception {
        AtomicReference<Object[]> capturedArguments = new AtomicReference<>();
        TeachingTaskMapper mapper = (TeachingTaskMapper) Proxy.newProxyInstance(
                TeachingTaskMapper.class.getClassLoader(),
                new Class<?>[] {TeachingTaskMapper.class},
                (proxy, method, arguments) -> {
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
                "status IN ('FAILED', 'RUNNING', 'COMPLETED')");
    }

    private static TeachingTaskResponse runningTask() {
        return new TeachingTaskResponse(
                "task-failed", "req-1", "tenant-a", "teacher", "teacher-1", null,
                TeachingTaskStatus.RUNNING, "求三角形的解", "正弦定理分类讨论", null,
                List.of(), List.of(), List.of(), List.of(), "", "", "", "", List.of(), null,
                List.of(), null, null, null, null, null);
    }
}
