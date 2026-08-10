package com.doob.mathagent.teacher.service;

import com.doob.mathagent.resources.ProjectResourceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes abandoned browser-upload staging trees after a bounded retention window.
 *
 * <p>Archive cleanup handles a successful resource, but a crashed request can leave a UUID tree with no database
 * row.  This component only traverses the upload service's own four-level root and never interprets arbitrary
 * {@code localPath} values, so local development paths remain untouched.</p>
 */
@Component
public class TeacherResourceUploadStagingCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherResourceUploadStagingCleanup.class);
    private static final String UPLOAD_ROOT_NAME = "teacher-resource-uploads";
    private static final String VISUAL_MATERIALIZATION_PREFIX = "math-agent-teacher-asset-";
    private static final int UPLOAD_DIRECTORY_DEPTH = 4;
    private static final int WALK_DEPTH = UPLOAD_DIRECTORY_DEPTH + 1;
    private static final long DEFAULT_RETENTION_HOURS = 24L;
    private static final long DEFAULT_SCAN_INTERVAL_MILLISECONDS = 3_600_000L;

    private final ProjectResourceProperties resourceProperties;
    private final Clock clock;
    private final Duration retention;
    private final Path temporaryDirectory;

    @Autowired
    public TeacherResourceUploadStagingCleanup(
            ProjectResourceProperties resourceProperties,
            org.springframework.core.env.Environment environment) {
        this(resourceProperties, Clock.systemUTC(),
                positiveLong(environment, "math-agent.teacher.upload.staging-retention-hours", DEFAULT_RETENTION_HOURS),
                systemTemporaryDirectory());
    }

    /** Injectable constructor for deterministic filesystem tests. */
    TeacherResourceUploadStagingCleanup(ProjectResourceProperties resourceProperties, Clock clock, long retentionHours) {
        this(resourceProperties, clock, retentionHours, systemTemporaryDirectory());
    }

    /** Injectable constructor also controls the temp root so cleanup tests use a real isolated filesystem tree. */
    TeacherResourceUploadStagingCleanup(
            ProjectResourceProperties resourceProperties,
            Clock clock,
            long retentionHours,
            Path temporaryDirectory) {
        this.resourceProperties = resourceProperties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.retention = Duration.ofHours(Math.max(1L, retentionHours));
        this.temporaryDirectory = temporaryDirectory == null
                ? systemTemporaryDirectory()
                : temporaryDirectory.toAbsolutePath().normalize();
    }

    /** Scans only backend-owned upload and visual-materialization namespaces; failures are retried next sweep. */
    @Scheduled(fixedDelayString = "${math-agent.teacher.upload.staging-cleanup-interval-ms:3600000}")
    public void removeExpiredStagingTrees() {
        if (resourceProperties != null && resourceProperties.localFileStorageRoot() != null) {
            Path root = resourceProperties.localFileStorageRoot()
                    .resolve(UPLOAD_ROOT_NAME).toAbsolutePath().normalize();
            removeExpiredUploadTrees(root);
        }
        removeExpiredVisualMaterializations(clock.instant().minus(retention));
    }

    private void removeExpiredUploadTrees(Path root) {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = clock.instant().minus(retention);
        try (Stream<Path> paths = Files.walk(root, WALK_DEPTH)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> relativeDepth(root, path) == UPLOAD_DIRECTORY_DEPTH)
                    .filter(path -> lastModified(path).isBefore(cutoff))
                    .forEach(this::deleteTree);
        } catch (IOException exception) {
            LOGGER.warn("teacher_upload_staging_cleanup_scan_failed root={} errorType={}", root,
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * Removes only temporary copies created for a non-file/object-store visual evidence adapter. Persistent teacher
     * assets live below the configured asset root and are intentionally outside this sweep; source archive owns them.
     */
    private void removeExpiredVisualMaterializations(Instant cutoff) {
        if (!Files.isDirectory(temporaryDirectory)) return;
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(VISUAL_MATERIALIZATION_PREFIX))
                    .filter(path -> lastModified(path).isBefore(cutoff))
                    .forEach(this::deleteTree);
        } catch (IOException exception) {
            LOGGER.warn("teacher_visual_materialization_cleanup_scan_failed root={} errorType={}", temporaryDirectory,
                    exception.getClass().getSimpleName());
        }
    }

    private void deleteTree(Path candidate) {
        try (Stream<Path> paths = Files.walk(candidate)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to remove expired teacher upload staging", exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("teacher_upload_staging_cleanup_failed path={} errorType={}", candidate,
                    exception.getClass().getSimpleName());
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.MAX;
        }
    }

    private static int relativeDepth(Path root, Path candidate) {
        return root.relativize(candidate).getNameCount();
    }

    private static Path systemTemporaryDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }

    private static long positiveLong(
            org.springframework.core.env.Environment environment, String key, long fallback) {
        try {
            return Long.parseLong(environment.getProperty(key, String.valueOf(fallback)).strip());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
