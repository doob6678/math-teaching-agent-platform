package com.doob.mathagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.memory.controller.StudentMemoryController;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StudentMemoryControllerTest {

    @Test
    void requestBodyStudentIdCannotWriteAnotherStudentsPrivateMemory() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore());
        StudentMemoryController controller = new StudentMemoryController(
                service,
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "student-spoofed");

        controller.remember(new StudentMemoryRequest(
                "school-a",
                "admin",
                "student-victim",
                "空间向量数量积求夹角",
                "用数量积公式先求 cosθ。",
                "空间向量数量积",
                "private",
                false), request);

        StudentMemoryResponse victim = service.reuse(new StudentMemoryRequest(
                "school-a",
                "student",
                "student-victim",
                "空间向量数量积求夹角",
                null,
                "空间向量数量积",
                "private",
                false));
        StudentMemoryResponse owner = service.reuse(new StudentMemoryRequest(
                "school-a",
                "student",
                "student-real",
                "空间向量数量积求夹角",
                null,
                "空间向量数量积",
                "private",
                false));

        assertThat(victim.reused()).isFalse();
        assertThat(owner.reused()).isTrue();
    }
}
