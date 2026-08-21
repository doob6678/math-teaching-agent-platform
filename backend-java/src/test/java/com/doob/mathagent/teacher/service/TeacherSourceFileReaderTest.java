package com.doob.mathagent.teacher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the persisted source catalog remains aligned with a readable source root. */
class TeacherSourceFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void registeredRootSurvivesReaderRecreationAndMissingRegisteredRootIsRejected() throws Exception {
        Path stagingRoot = tempDir.resolve("teacher-source-imports");
        Path sourceRoot = tempDir.resolve("teacher-resource-uploads").resolve("document-1");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("lesson.md"), "可读取的抛物线资料");
        TeacherSourceSyncProperties properties = properties(stagingRoot);

        TeacherSourceFileReader firstReader = new TeacherSourceFileReader(properties);
        firstReader.register("school-a", "document-1", sourceRoot, "checksum-1");

        TeacherSourceFileReader recreatedReader = new TeacherSourceFileReader(properties);
        assertThat(recreatedReader.isSourceAvailable("school-a", "document-1")).isTrue();
        assertThat(recreatedReader.read("school-a", "document-1").files())
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.relativeName()).isEqualTo("lesson.md");
                    assertThat(file.text()).isEqualTo("可读取的抛物线资料");
                });

        Files.delete(sourceRoot.resolve("lesson.md"));
        Files.delete(sourceRoot);

        assertThat(recreatedReader.isSourceAvailable("school-a", "document-1")).isFalse();
        assertThatThrownBy(() -> recreatedReader.read("school-a", "document-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Registered source root is unavailable");
    }

    private static TeacherSourceSyncProperties properties(Path stagingRoot) {
        return new TeacherSourceSyncProperties(
                "", stagingRoot.resolve("download.py"), stagingRoot.resolve("appkey"), stagingRoot,
                stagingRoot.resolve("assets"), 1, 30);
    }
}
