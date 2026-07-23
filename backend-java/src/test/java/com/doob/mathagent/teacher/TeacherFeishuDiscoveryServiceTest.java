package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.feishu.TeacherFeishuDiscoveryClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDiscoveryQuery;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherFeishuDiscoveryServiceTest {

    @Test
    void teacherListsFeishuRootThroughBackendRole() {
        RecordingDiscoveryClient client = new RecordingDiscoveryClient(response("list_root", "", 2));
        TeacherFeishuDiscoveryService service = new TeacherFeishuDiscoveryService(client);

        TeacherFeishuDiscoveryResponse response = service.discover(
                "school-a",
                "teacher",
                "teacher-1",
                "list",
                "",
                "https://my.feishu.cn/drive/folder/root-token",
                2,
                8);

        assertThat(client.query.mode()).isEqualTo("list");
        assertThat(client.query.rootUrl()).isEqualTo("https://my.feishu.cn/drive/folder/root-token");
        assertThat(client.query.listDepth()).isEqualTo(2);
        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.candidates().getFirst().path()).isEqualTo("函数/分段函数");
    }

    @Test
    void searchRequiresKeywordAndClampsDepth() {
        RecordingDiscoveryClient client = new RecordingDiscoveryClient(response("search_root", "空间向量", 5));
        TeacherFeishuDiscoveryService service = new TeacherFeishuDiscoveryService(client);

        TeacherFeishuDiscoveryResponse response = service.discover(
                "school-a",
                "admin",
                "admin-1",
                "search",
                "空间向量",
                "https://my.feishu.cn/drive/folder/root-token",
                99,
                99);

        assertThat(client.query.mode()).isEqualTo("search");
        assertThat(client.query.keyword()).isEqualTo("空间向量");
        assertThat(client.query.listDepth()).isEqualTo(3);
        assertThat(client.query.maxDepth()).isEqualTo(8);
        assertThat(response.keyword()).isEqualTo("空间向量");
    }

    @Test
    void rejectsStudentFeishuDiscovery() {
        TeacherFeishuDiscoveryService service = new TeacherFeishuDiscoveryService(
                query -> response("list_root", "", 1));

        assertThatThrownBy(() -> service.discover(
                        "school-a",
                        "student",
                        "student-1",
                        "list",
                        "",
                        "",
                        1,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    @Test
    void rejectsBlankSearchKeyword() {
        TeacherFeishuDiscoveryService service = new TeacherFeishuDiscoveryService(
                query -> response("search_root", "", 1));

        assertThatThrownBy(() -> service.discover(
                        "school-a",
                        "teacher",
                        "teacher-1",
                        "search",
                        " ",
                        "https://my.feishu.cn/drive/folder/root-token",
                        1,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyword");
    }

    @Test
    void rejectsBlankRootUrl() {
        TeacherFeishuDiscoveryService service = new TeacherFeishuDiscoveryService(
                query -> response("list_root", "", 1));

        assertThatThrownBy(() -> service.discover(
                        "school-a",
                        "teacher",
                        "teacher-1",
                        "list",
                        "",
                        " ",
                        1,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rootUrl");
    }

    private static TeacherFeishuDiscoveryResponse response(String mode, String keyword, int depth) {
        return new TeacherFeishuDiscoveryResponse(
                "query-1",
                mode,
                "https://my.feishu.cn/drive/folder/root-token",
                keyword,
                depth,
                1,
                List.of(new TeacherFeishuDiscoveryResponse.Candidate(
                        "docx",
                        "doc-token",
                        "分段函数",
                        "函数/分段函数",
                        "https://my.feishu.cn/docx/doc-token",
                        2,
                        true)),
                "ok",
                "Found 1 Feishu candidates");
    }

    private static final class RecordingDiscoveryClient implements TeacherFeishuDiscoveryClient {

        private final TeacherFeishuDiscoveryResponse response;
        private TeacherFeishuDiscoveryQuery query;

        private RecordingDiscoveryClient(TeacherFeishuDiscoveryResponse response) {
            this.response = response;
        }

        @Override
        public TeacherFeishuDiscoveryResponse discover(TeacherFeishuDiscoveryQuery query) {
            this.query = query;
            return response;
        }
    }
}
