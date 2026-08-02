package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceAssetStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherResourceAssetServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void ownerAndAdminCanReadPrivateAssetButOtherTeacherAndStudentCannot() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        TeacherResourceDocumentResponse document = document("doc-private", "teacher-1", "TEACHER_PRIVATE");
        resourceStore.save(document);
        TeacherResourceAssetResponse asset = service.saveExtractedAsset(
                        document,
                        "private-note.docx",
                        2,
                        "docx:/word/media/image1.png",
                        pngBytes(),
                        "image/png")
                .orElseThrow();

        assertThat(service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-1")).mimeType())
                .isEqualTo("image/png");
        assertThat(service.openVisibleAsset(asset.assetId(), subject("admin", "admin-1")).fileName())
                .contains("private-note.docx");
        assertThatThrownBy(() -> service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
        assertThatThrownBy(() -> service.openVisibleAsset(asset.assetId(), subject("student", "student-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
    }

    @Test
    void sharedAssetIsReadableBySameTenantTeacherButNotStudent() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        TeacherResourceDocumentResponse document = document("doc-shared", "teacher-1", "MATH_VIP");
        resourceStore.save(document);
        TeacherResourceAssetResponse asset = service.saveExtractedAsset(
                        document,
                        "shared-bank.pdf",
                        1,
                        "pdf-page:1",
                        pngBytes(),
                        "image/png")
                .orElseThrow();

        assertThat(service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-2")).assetId())
                .isEqualTo(asset.assetId());
        assertThatThrownBy(() -> service.openVisibleAsset(asset.assetId(), subject("student", "student-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
    }

    @Test
    void archivedSharedDocumentStopsCrossTeacherAssetReads() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                "doc-archived",
                "school-a",
                "teacher-1",
                "local_path",
                "Archived shared notes",
                null,
                tempDir.resolve("archived").toString(),
                "MATH_VIP",
                "archived",
                "parsed",
                "ready",
                "ready",
                null,
                java.util.List.of(),
                "TEXT");
        resourceStore.save(document);
        TeacherResourceAssetResponse asset = service.saveExtractedAsset(
                        document,
                        "shared-archived.pdf",
                        1,
                        "pdf-page:1",
                        pngBytes(),
                        "image/png")
                .orElseThrow();

        assertThatThrownBy(() -> service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
        assertThat(service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-1")).assetId())
                .isEqualTo(asset.assetId());
    }

    @Test
    void restoresMissingBinaryFromRegisteredLocalSourceOnRead() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        Path source = tempDir.resolve("source-image.png");
        Files.write(source, pngBytes());
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                "doc-local-source", "school-a", "teacher-1", "local_path", "Local source", null,
                source.toString(), "TEACHER_PRIVATE", "synced", "parsed", "ready", "ready", null,
                java.util.List.of(), "TEXT");
        resourceStore.save(document);
        TeacherResourceAssetResponse asset = service.saveExtractedAsset(
                document, "source-image.png", null, "local-image", pngBytes(), "image/png").orElseThrow();
        Files.delete(testSyncProperties().assetStorageRoot().resolve(asset.storageKey()));

        TeacherResourceAssetService.VisibleAsset restored =
                service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-1"));
        assertThat(restored.resource().exists()).isTrue();
        assertThat(Files.readAllBytes(restored.resource().getFile().toPath())).isEqualTo(pngBytes());
    }

    @Test
    void resyncRewritesExistingAssetWhenStorageFileWasRemoved() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        TeacherResourceDocumentResponse document = document("doc-resync", "teacher-1", "TEACHER_PRIVATE");
        resourceStore.save(document);
        byte[] content = pngBytes();
        TeacherResourceAssetResponse first = service.saveExtractedAsset(
                document, "resync.png", 1, "local-image", content, "image/png").orElseThrow();
        Path storage = testSyncProperties().assetStorageRoot().resolve(first.storageKey());
        Files.delete(storage);

        TeacherResourceAssetResponse second = service.saveExtractedAsset(
                document, "resync.png", 1, "local-image", content, "image/png").orElseThrow();
        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(Files.readAllBytes(storage)).isEqualTo(content);
    }

    @Test
    void restoresMissingPdfPageAssetFromRegisteredPdfSource() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService service =
                new TeacherResourceAssetService(assetStore, resourceStore, testSyncProperties());
        Path source = tempDir.resolve("source.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(source.toFile());
        }
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                "doc-local-pdf", "school-a", "teacher-1", "local_path", "Local PDF", null,
                source.toString(), "TEACHER_PRIVATE", "synced", "parsed", "ready", "ready", null,
                java.util.List.of(), "TEXT");
        resourceStore.save(document);
        TeacherResourceAssetResponse asset = service.saveExtractedAsset(
                document, "source.pdf", 1, "pdf-page:1", pngBytes(), "image/png").orElseThrow();
        Files.delete(testSyncProperties().assetStorageRoot().resolve(asset.storageKey()));

        TeacherResourceAssetService.VisibleAsset restored =
                service.openVisibleAsset(asset.assetId(), subject("teacher", "teacher-1"));
        assertThat(restored.resource().contentLength()).isGreaterThan(100L);
        assertThat(restored.mimeType()).isEqualTo("image/png");
    }

    private TeacherSourceSyncProperties testSyncProperties() {
        return new TeacherSourceSyncProperties(
                "",
                tempDir.resolve("download_feishu_url.py"),
                tempDir.resolve("APPKEY.md"),
                tempDir.resolve("feishu-staging"),
                1,
                30);
    }

    private static TeacherResourceDocumentResponse document(String documentId, String ownerSubjectId, String permissionScope) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "school-a",
                ownerSubjectId,
                "local_path",
                "Asset visibility doc",
                null,
                "C:/tmp/asset-visibility",
                permissionScope,
                "synced",
                "parsed",
                "ready",
                "ready",
                null,
                java.util.List.of(),
                "TEXT");
    }

    private static RequestSubject subject(String role, String subjectId) {
        return new RequestSubject("school-a", role, subjectId, "device-1");
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x += 1) {
            for (int y = 0; y < image.getHeight(); y += 1) {
                image.setRGB(x, y, x < 6 ? Color.RED.getRGB() : Color.GREEN.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

