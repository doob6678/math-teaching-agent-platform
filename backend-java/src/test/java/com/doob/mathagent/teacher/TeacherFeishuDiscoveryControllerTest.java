package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.controller.TeacherFeishuDiscoveryController;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDiscoveryClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDiscoveryQuery;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class TeacherFeishuDiscoveryControllerTest {

    @Test
    void discoversFeishuCandidatesWithBackendSubjectAndIgnoresSpoofedHeaders() {
        RecordingDiscoveryClient client = new RecordingDiscoveryClient();
        TeacherFeishuDiscoveryController controller = new TeacherFeishuDiscoveryController(
                new TeacherFeishuDiscoveryService(client),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "student");
        request.addHeader("X-Subject-Id", "student-spoofed");

        TeacherFeishuDiscoveryResponse response = controller.discover(
                "search",
                "空间向量",
                "https://my.feishu.cn/drive/folder/root-token",
                1,
                5,
                request);

        assertThat(client.query.mode()).isEqualTo("search");
        assertThat(client.query.keyword()).isEqualTo("空间向量");
        assertThat(response.candidates()).extracting(TeacherFeishuDiscoveryResponse.Candidate::token)
                .containsExactly("doc-token");
    }

    @Test
    void rejectsStudentDiscoveryAtControllerBoundary() {
        TeacherFeishuDiscoveryController controller = new TeacherFeishuDiscoveryController(
                new TeacherFeishuDiscoveryService(query -> response()),
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.discover(
                        "list",
                        "",
                        "",
                        1,
                        1,
                        new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("teacher or admin");
    }

    private static TeacherFeishuDiscoveryResponse response() {
        return new TeacherFeishuDiscoveryResponse(
                "query-1",
                "search_root",
                "https://my.feishu.cn/drive/folder/root-token",
                "空间向量",
                5,
                1,
                List.of(new TeacherFeishuDiscoveryResponse.Candidate(
                        "docx",
                        "doc-token",
                        "空间向量",
                        "必修二/空间向量",
                        "https://my.feishu.cn/docx/doc-token",
                        2,
                        true)),
                "ok",
                "Found 1 Feishu candidates");
    }

    private static final class RecordingDiscoveryClient implements TeacherFeishuDiscoveryClient {

        private TeacherFeishuDiscoveryQuery query;

        @Override
        public TeacherFeishuDiscoveryResponse discover(TeacherFeishuDiscoveryQuery query) {
            this.query = query;
            return response();
        }
    }
}
