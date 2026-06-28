package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.controller.TeacherResourceController;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class TeacherResourceControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void controllerBuildsTeacherContextFromHeaders() throws Exception {
        Path folder = tempDir.resolve("teacher-bank");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("函数.md"), "# 函数题型");

        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(new InMemoryTeacherResourceStore()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "school-a");
        request.addHeader("X-Subject-Type", "teacher");
        request.addHeader("X-Subject-Id", "teacher-88");

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                null,
                null,
                null,
                "local_path",
                "函数本地题库",
                null,
                folder.toString(),
                "MATH_VIP"), request);

        assertThat(response.tenantId()).isEqualTo("school-a");
        assertThat(response.ownerSubjectId()).isEqualTo("teacher-88");
        assertThat(controller.list(request)).extracting(TeacherResourceDocumentResponse::title)
                .contains("函数本地题库");
    }
}
