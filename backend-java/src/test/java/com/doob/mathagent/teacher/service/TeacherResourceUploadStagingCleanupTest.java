package com.doob.mathagent.teacher.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.resources.ProjectResourceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherResourceUploadStagingCleanupTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(1);

    @Test
    void removesOnlyExpiredManagedUploadAndVisualMaterializationTrees() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path storageRoot = workspace.resolve("storage");
        Path temporaryRoot = workspace.resolve("tmp");
        Files.createDirectories(storageRoot);
        Files.createDirectories(temporaryRoot);

        Path expiredUpload = storageRoot.resolve("teacher-resource-uploads/tenant/teacher/user/expired");
        Path freshUpload = storageRoot.resolve("teacher-resource-uploads/tenant/teacher/user/fresh");
        Path expiredVisual = temporaryRoot.resolve("math-agent-teacher-asset-expired");
        Path freshVisual = temporaryRoot.resolve("math-agent-teacher-asset-fresh");
        writeMarker(expiredUpload.resolve("source.pdf"));
        writeMarker(freshUpload.resolve("source.pdf"));
        writeMarker(expiredVisual.resolve("asset.png"));
        writeMarker(freshVisual.resolve("asset.png"));
        FileTime expiredTime = FileTime.from(NOW.minus(RETENTION).minus(Duration.ofMinutes(1)));
        Files.setLastModifiedTime(expiredUpload, expiredTime);
        Files.setLastModifiedTime(expiredVisual, expiredTime);

        TeacherResourceUploadStagingCleanup cleanup = new TeacherResourceUploadStagingCleanup(
                new ProjectResourceProperties(storageRoot, storageRoot, storageRoot, storageRoot, storageRoot),
                Clock.fixed(NOW, ZoneOffset.UTC), RETENTION.toHours(), temporaryRoot);

        cleanup.removeExpiredStagingTrees();

        assertThat(expiredUpload).doesNotExist();
        assertThat(expiredVisual).doesNotExist();
        assertThat(freshUpload).exists();
        assertThat(freshVisual).exists();
    }

    private static void writeMarker(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "owned temporary evidence");
    }

    @TempDir
    Path tempDir;
}
