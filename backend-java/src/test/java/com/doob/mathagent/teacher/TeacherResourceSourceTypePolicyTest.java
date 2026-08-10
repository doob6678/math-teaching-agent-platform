package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.support.TeacherResourceSourceTypePolicy;
import org.junit.jupiter.api.Test;

class TeacherResourceSourceTypePolicyTest {

    @Test
    void writableCategoriesAreCanonicalAndLegacyLocalPathMapsToTeacherResource() {
        assertThat(TeacherResourceSourceTypePolicy.normalizeForRegistration(null)).isEqualTo("teacher_resource");
        assertThat(TeacherResourceSourceTypePolicy.normalizeForRegistration("local_path")).isEqualTo("teacher_resource");
        assertThat(TeacherResourceSourceTypePolicy.normalizeForRegistration("FEISHU")).isEqualTo("feishu");
        assertThat(TeacherResourceSourceTypePolicy.normalizeForRegistration("gaokao")).isEqualTo("gaokao");
        assertThat(TeacherResourceSourceTypePolicy.normalizeForRegistration("mock_exam")).isEqualTo("mock_exam");
    }

    @Test
    void unknownCategoryCannotBeWrittenAndThereforeCannotChangeOnRename() {
        assertThatThrownBy(() -> TeacherResourceSourceTypePolicy.normalizeForRegistration("title-says-feishu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported teacher-resource sourceType");
    }
}
