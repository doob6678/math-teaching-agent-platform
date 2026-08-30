package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectResourcePropertiesTest {

    @Test
    void readsAllLocalResourcePathsFromEnvironmentMap() {
        ProjectResourceProperties properties = ProjectResourceProperties.fromEnvironment(Map.of(
                "MATH_AGENT_PROJECT_TEST_DATA_ROOT", "C:/project/data",
                "MATH_AGENT_DESIGN_SPEC_ROOT", "C:/project/design",
                "MATH_AGENT_REFERENCE_HANDOUT_PDF", "C:/project/data/reference.pdf",
                "MATH_AGENT_PROMPT_DESIGN_PDF", "C:/project/prompt.pdf",
                "MATH_AGENT_LOCAL_FILE_STORAGE_ROOT", "C:/project/storage",
                "MATH_AGENT_TEACHER_RESOURCE_UPLOAD_ROOT", "C:/project/teacher-uploads"));

        assertThat(properties.projectTestDataRoot()).isEqualTo(normalized("C:/project/data"));
        assertThat(properties.designSpecRoot()).isEqualTo(normalized("C:/project/design"));
        assertThat(properties.referenceHandoutPdf()).isEqualTo(normalized("C:/project/data/reference.pdf"));
        assertThat(properties.promptDesignPdf()).isEqualTo(normalized("C:/project/prompt.pdf"));
        assertThat(properties.localFileStorageRoot()).isEqualTo(normalized("C:/project/storage"));
        assertThat(properties.teacherResourceUploadRoot()).isEqualTo(normalized("C:/project/teacher-uploads"));
    }

    @Test
    void failsFastWhenRequiredResourcePathsAreMissing() {
        assertThatThrownBy(() -> ProjectResourceProperties.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_PROJECT_TEST_DATA_ROOT");
    }

    private static Path normalized(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
