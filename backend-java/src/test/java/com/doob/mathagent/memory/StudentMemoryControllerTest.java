package com.doob.mathagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.memory.controller.StudentMemoryController;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class StudentMemoryControllerTest {

    @Test
    void requestBodyCannotWriteAnotherStudentsPrivateMemory() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore());
        StudentMemoryController controller = new StudentMemoryController(
                service,
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "student-spoofed");

        controller.remember(new StudentMemoryRequest(
                "vector dot product angle",
                "Use a dot b = |a||b|cos(theta) first.",
                "vector dot product",
                "private",
                false), request);

        StudentMemoryResponse victim = service.reuse(new StudentMemoryCommand(
                "school-a",
                "student",
                "student-victim",
                "vector dot product angle",
                null,
                "vector dot product",
                "private",
                false));
        StudentMemoryResponse owner = service.reuse(new StudentMemoryCommand(
                "school-a",
                "student",
                "student-real",
                "vector dot product angle",
                null,
                "vector dot product",
                "private",
                false));

        assertThat(victim.reused()).isFalse();
        assertThat(owner.reused()).isTrue();
    }

    @Test
    void rejectsRememberWithoutAcceptedCapabilityToken() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore());
        StudentMemoryController controller = new StudentMemoryController(
                service,
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.remember(
                        new StudentMemoryRequest(
                                "vector dot product angle",
                                "Use a dot b = |a||b|cos(theta) first.",
                                "vector dot product",
                                "private",
                                false),
                        requestWithCapability("bad-token", "hash-memory")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    /**
     * Builds an HTTP request carrying capability headers for controller tests.
     */
    private static MockHttpServletRequest requestWithCapability(String token, String requestHash) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Capability-Token", token);
        request.addHeader("X-Request-Hash", requestHash);
        return request;
    }
}
