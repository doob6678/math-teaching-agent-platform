package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.student.service.StudentExplanationImageRecord;
import com.doob.mathagent.student.service.StudentExplanationImageStoreService;
import com.doob.mathagent.student.vo.StudentExplanationImageUploadResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class StudentExplanationImageStoreServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesImageUnderOwnerScopeAndReturnsTemporaryMetadata() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        StudentExplanationImageStoreService store = store(clock, Duration.ofMinutes(30), 1024);
        RequestSubject owner = new RequestSubject("tenant-a", "student", "student-1", "device-1");

        StudentExplanationImageUploadResponse response = store.save(
                image("question.png", "image/png", new byte[] {1, 2, 3}),
                owner);
        StudentExplanationImageRecord record = store.findUsable(response.uploadId(), owner).orElseThrow();

        assertThat(response.originalFileName()).isEqualTo("question.png");
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.sizeBytes()).isEqualTo(3);
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-07-01T00:30:00Z"));
        assertThat(response.imageStatus()).isEqualTo("image_uploaded_without_vision_analysis");
        assertThat(record.localPath()).exists();
        assertThat(record.localPath().toString()).contains("tenant-a").contains("student").contains("student-1");
    }

    @Test
    void rejectsNonImageContentType() {
        StudentExplanationImageStoreService store = store(new MutableClock(), Duration.ofMinutes(30), 1024);

        assertThatThrownBy(() -> store.save(
                        new MockMultipartFile("file", "question.txt", "text/plain", "abc".getBytes()),
                        new RequestSubject("school-a", "student", "student-001", "dev-device")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only image uploads");
    }

    @Test
    void rejectsFilesAboveConfiguredLimit() {
        StudentExplanationImageStoreService store = store(new MutableClock(), Duration.ofMinutes(30), 2);

        assertThatThrownBy(() -> store.save(
                        image("question.png", "image/png", new byte[] {1, 2, 3}),
                        new RequestSubject("school-a", "student", "student-001", "dev-device")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max size");
    }

    @Test
    void rejectsAccessFromDifferentOwner() {
        StudentExplanationImageStoreService store = store(new MutableClock(), Duration.ofMinutes(30), 1024);
        RequestSubject owner = new RequestSubject("tenant-a", "student", "student-1", "device-1");
        RequestSubject otherStudent = new RequestSubject("tenant-a", "student", "student-2", "device-1");
        StudentExplanationImageUploadResponse response = store.save(
                image("question.png", "image/png", new byte[] {1, 2, 3}),
                owner);

        assertThatThrownBy(() -> store.findUsable(response.uploadId(), otherStudent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not owned");
    }

    @Test
    void expiredUploadIsRemovedAndCannotBeUsed() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        StudentExplanationImageStoreService store = store(clock, Duration.ofSeconds(10), 1024);
        RequestSubject owner = new RequestSubject("school-a", "student", "student-001", "dev-device");
        StudentExplanationImageUploadResponse response = store.save(
                image("question.png", "image/png", new byte[] {1, 2, 3}),
                owner);
        Path storedFile = store.findUsable(response.uploadId(), owner).orElseThrow().localPath();

        clock.advance(Duration.ofSeconds(10));

        assertThat(store.findUsable(response.uploadId(), owner)).isEmpty();
        assertThat(Files.exists(storedFile)).isFalse();
    }

    @Test
    void nonExpiredUploadMetadataSurvivesServiceRestart() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        RequestSubject owner = new RequestSubject("tenant-a", "student", "student-1", "device-1");
        StudentExplanationImageStoreService firstStore = store(clock, Duration.ofMinutes(30), 1024);
        StudentExplanationImageUploadResponse response = firstStore.save(
                image("question.png", "image/png", new byte[] {1, 2, 3}),
                owner);

        StudentExplanationImageStoreService restartedStore = store(clock, Duration.ofMinutes(30), 1024);
        StudentExplanationImageRecord recovered = restartedStore.findUsable(response.uploadId(), owner).orElseThrow();

        assertThat(recovered.uploadId()).isEqualTo(response.uploadId());
        assertThat(recovered.originalFileName()).isEqualTo("question.png");
        assertThat(recovered.localPath()).exists();
    }

    @Test
    void expiredUploadMetadataFromPreviousProcessIsCleaned() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        RequestSubject owner = new RequestSubject("tenant-a", "student", "student-1", "device-1");
        StudentExplanationImageStoreService firstStore = store(clock, Duration.ofSeconds(10), 1024);
        StudentExplanationImageUploadResponse response = firstStore.save(
                image("question.png", "image/png", new byte[] {1, 2, 3}),
                owner);

        clock.advance(Duration.ofSeconds(10));
        StudentExplanationImageStoreService restartedStore = store(clock, Duration.ofSeconds(10), 1024);

        assertThat(restartedStore.findUsable(response.uploadId(), owner)).isEmpty();
        assertThat(Files.exists(tempDir.resolve("student-explanation-images"))).isTrue();
        assertThat(restartedStore.cleanupExpired()).isEqualTo(0);
    }

    /**
     * Creates an image store with all project paths redirected to the JUnit temp directory.
     */
    private StudentExplanationImageStoreService store(MutableClock clock, Duration ttl, long maxBytes) {
        return new StudentExplanationImageStoreService(
                new ProjectResourceProperties(tempDir, tempDir, tempDir, tempDir, tempDir),
                clock,
                ttl,
                maxBytes);
    }

    /**
     * Creates a multipart image with real binary bytes.
     */
    private static MockMultipartFile image(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    /**
     * Test clock that can be advanced to verify expiration without sleeping.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        /**
         * Creates a clock starting at the Unix epoch.
         */
        private MutableClock() {
            this(Instant.EPOCH);
        }

        /**
         * Creates a clock starting at a specific instant.
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * Advances the current instant.
         */
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
