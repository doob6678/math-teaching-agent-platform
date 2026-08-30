package com.doob.mathagent.vector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceAssetStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherResourceImageClipServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void isolatesWorkerRejectedImageAndKeepsHealthyImageIndexed() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherSourceSyncProperties syncProperties = syncProperties();
        TeacherResourceAssetService assetService = new TeacherResourceAssetService(assetStore, resourceStore, syncProperties);
        TeacherResourceDocumentResponse document = document();
        resourceStore.save(document);

        byte[] healthyBytes = pngBytes(Color.RED);
        byte[] rejectedBytes = pngBytes(Color.BLUE);
        TeacherResourceAssetResponse healthy = assetService.saveExtractedAsset(
                document, "IMAJES/healthy.png", null, "healthy", healthyBytes, "image/png").orElseThrow();
        TeacherResourceAssetResponse rejected = assetService.saveExtractedAsset(
                document, "IMAJES/rejected.png", null, "rejected", rejectedBytes, "image/png").orElseThrow();

        CapturingClipTransport transport = new CapturingClipTransport(
                Base64.getEncoder().encodeToString(rejectedBytes), false);
        TeacherResourceImageClipService service = new TeacherResourceImageClipService(
                properties(), transport, resourceStore, assetService);

        TeacherResourceImageClipIndexResponse response = service.indexDocument(
                "school-a", "teacher", "teacher-1", document.documentId());

        assertThat(response.assetCount()).isEqualTo(2);
        assertThat(response.embeddedCount()).isEqualTo(1);
        assertThat(response.upsertedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.failedAssetIds()).containsExactly(rejected.assetId());
        assertThat(transport.imageEmbeddingRequests).isEqualTo(3);
        assertThat(transport.upsertRequests).isEqualTo(1);
        assertThat(assetStore.find("school-a", healthy.assetId()).orElseThrow().status()).isEqualTo("active");
        assertThat(assetStore.find("school-a", rejected.assetId()).orElseThrow().status()).isEqualTo("inactive");
    }

    @Test
    void keepsAssetsActiveWhenEverySingleImageRequestFails() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceAssetService assetService = new TeacherResourceAssetService(assetStore, resourceStore, syncProperties());
        TeacherResourceDocumentResponse document = document();
        resourceStore.save(document);
        TeacherResourceAssetResponse first = assetService.saveExtractedAsset(
                document, "IMAJES/first.png", null, "first", pngBytes(Color.RED), "image/png").orElseThrow();
        TeacherResourceAssetResponse second = assetService.saveExtractedAsset(
                document, "IMAJES/second.png", null, "second", pngBytes(Color.GREEN), "image/png").orElseThrow();

        TeacherResourceImageClipService service = new TeacherResourceImageClipService(
                properties(), new CapturingClipTransport("", true), resourceStore, assetService);

        assertThatThrownBy(() -> service.indexDocument("school-a", "teacher", "teacher-1", document.documentId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIP embedding returned no vectors");
        assertThat(assetStore.find("school-a", first.assetId()).orElseThrow().status()).isEqualTo("active");
        assertThat(assetStore.find("school-a", second.assetId()).orElseThrow().status()).isEqualTo("active");
    }

    private TeacherSourceSyncProperties syncProperties() {
        return new TeacherSourceSyncProperties(
                "",
                tempDir.resolve("download_feishu_url.py"),
                tempDir.resolve("APPKEY.md"),
                tempDir.resolve("feishu-staging"),
                tempDir.resolve("teacher-assets"),
                1,
                30);
    }

    private TeacherResourceDocumentResponse document() {
        return new TeacherResourceDocumentResponse(
                "doc-clip-isolation",
                "school-a",
                "teacher-1",
                "feishu",
                "Parabola resources",
                null,
                tempDir.resolve("source").toString(),
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                "md",
                List.of(),
                "TEXT");
    }

    private static VectorIndexProperties properties() {
        return new VectorIndexProperties(
                true,
                "http://milvus.local:19530",
                "milvus-token",
                "math_agent_teacher_text_blocks_bge",
                "math_agent_student_memories_bge",
                "math_agent_textbook_pages_bge",
                "math_agent_textbook_pages_clip",
                3,
                3,
                3,
                3,
                "http://worker.local/v1",
                "worker-key",
                "clip-test",
                10000,
                10,
                true,
                "math_agent_teacher_page_assets_clip",
                3,
                3,
                null);
    }

    private static byte[] pngBytes(Color color) throws Exception {
        BufferedImage image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x += 1) {
            for (int y = 0; y < image.getHeight(); y += 1) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class CapturingClipTransport implements VectorHttpTransport {
        private final String rejectedImageBase64;
        private final boolean failAllImages;
        private int imageEmbeddingRequests;
        private int upsertRequests;

        private CapturingClipTransport(String rejectedImageBase64, boolean failAllImages) {
            this.rejectedImageBase64 = rejectedImageBase64;
            this.failAllImages = failAllImages;
        }

        @Override
        public VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
            String path = uri.getPath();
            if (path.endsWith("/clip/image-embeddings")) {
                imageEmbeddingRequests += 1;
                if (failAllImages || (!rejectedImageBase64.isBlank() && body.contains(rejectedImageBase64))) {
                    return new VectorHttpResponse(500, "{\"code\":500,\"message\":\"worker rejected image\"}");
                }
                int count = Math.max(1, body.split("data:image", -1).length - 1);
                return new VectorHttpResponse(200, embeddingResponse(count));
            }
            if (path.endsWith("/entities/upsert")) {
                upsertRequests += 1;
                int count = Math.max(1, body.split("\\\"id\\\"", -1).length - 1);
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"upsertCount\":" + count + "}}");
            }
            if (path.endsWith("/entities/delete")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            return new VectorHttpResponse(200, "{\"code\":0}");
        }

        private static String embeddingResponse(int count) {
            String vector = "{\"embedding\":[0.1,0.2,0.3]}";
            List<String> values = new ArrayList<>();
            for (int index = 0; index < count; index += 1) {
                values.add(vector);
            }
            return "{\"data\":[" + String.join(",", values) + "]}";
        }
    }
}
