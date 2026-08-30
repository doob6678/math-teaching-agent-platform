package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.controller.AgentToolBrokerController;
import com.doob.mathagent.agent.dto.HandoutContextRequest;
import com.doob.mathagent.agent.dto.HandoutDocumentReadRequest;
import com.doob.mathagent.agent.dto.HandoutDocumentPageReadRequest;
import com.doob.mathagent.agent.dto.HandoutTeacherResourceSearchRequest;
import com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceAssetStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherSourceFileReader;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.resources.TextbookAuthorizedBlockReader;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.service.TeachingTaskStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** 验证讲义上下文只能读取当前任务已经持久化的证据。 */
class AgentToolBrokerControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void publicTextbookEvidenceReceivesOpaqueReferenceAndReturnsParsedChunks() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-textbook-001";
        String docId = "textbook-001";
        Path bookRoot = tempDir.resolve(docId);
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(tempDir.resolve("catalog.jsonl"), """
                {"doc_id":"textbook-001","book_name":"教材","book_root":"textbook-001","chunk_count":1,"page_count":1}
                """, StandardCharsets.UTF_8);
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"chunk-001","doc_id":"textbook-001","page_no":12,"section_title":"抛物线","text":"抛物线到焦点与准线距离相等。"}
                """, StandardCharsets.UTF_8);
        TextbookAuthorizedBlockReader textbookReader = new TextbookAuthorizedBlockReader(
                new TextbookCatalogReader(), new TextbookChunkReader(),
                new com.doob.mathagent.resources.TextbookResourceProperties(tempDir));
        TeachingEvidence textbook = new TeachingEvidence("PUBLIC_TEXTBOOK", "教材", "chunk-001", 12,
                "抛物线摘要", "", "", docId, "public_textbook", "", "", List.of());
        AgentToolBrokerController controller = new AgentToolBrokerController(
                null, null, textbookReader,
                new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, new InMemoryTaskStore(task(runId, List.of(textbook))), null);
        String evidenceRef = evidenceRef(workerKey, runId, textbook);
        String documentRef = documentRef(workerKey, runId, docId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> context = (List<Map<String, Object>>) controller.handoutContext(workerKey,
                new HandoutContextRequest(runId, List.of(evidenceRef), 12)).get("items");
        assertThat(context).singleElement().satisfies(item -> {
            assertThat(item.get("documentRef")).isEqualTo(documentRef);
            assertThat(item).doesNotContainKeys("sourcePath", "sourceUrl", "docId");
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) controller.handoutDocumentRead(workerKey,
                new HandoutDocumentReadRequest(runId, documentRef, 8, 4_000)).get("blocks");
        assertThat(blocks).singleElement().satisfies(block ->
                assertThat(block.get("text")).isEqualTo("抛物线到焦点与准线距离相等。"));
    }

    @Test
    void textbookPageReadReturnsOnlyAuthorizedPageWindowAndRejectsAnotherPage() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-textbook-page-001";
        String docId = "textbook-page-001";
        Path bookRoot = tempDir.resolve(docId);
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(tempDir.resolve("catalog.jsonl"), """
                {"doc_id":"textbook-page-001","book_name":"教材","book_root":"textbook-page-001","chunk_count":3,"page_count":3}
                """, StandardCharsets.UTF_8);
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"page-08","doc_id":"textbook-page-001","page_no":8,"section_title":"窗口前页","text":"第八页原文"}
                {"chunk_id":"page-11","doc_id":"textbook-page-001","page_no":11,"section_title":"前页","text":"第十一页原文"}
                {"chunk_id":"page-12","doc_id":"textbook-page-001","page_no":12,"section_title":"命中页","text":"第十二页原文"}
                {"chunk_id":"page-13","doc_id":"textbook-page-001","page_no":13,"section_title":"后页","text":"第十三页原文"}
                {"chunk_id":"page-16","doc_id":"textbook-page-001","page_no":16,"section_title":"窗口后页","text":"第十六页原文"}
                """, StandardCharsets.UTF_8);
        TextbookAuthorizedBlockReader reader = new TextbookAuthorizedBlockReader(
                new TextbookCatalogReader(), new TextbookChunkReader(),
                new com.doob.mathagent.resources.TextbookResourceProperties(tempDir));
        TeachingEvidence textbook = new TeachingEvidence("PUBLIC_TEXTBOOK", "教材", "page-12", 12,
                "第十二页摘要", "", "", docId, "public_textbook", "", "", List.of());
        AgentToolBrokerController controller = new AgentToolBrokerController(
                null, null, reader,
                new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, new InMemoryTaskStore(task(runId, List.of(textbook))), null);
        String ref = documentRef(workerKey, runId, docId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) controller.handoutDocumentPageRead(workerKey,
                new HandoutDocumentPageReadRequest(runId, ref, 12, 4, 8, 4_000)).get("blocks");
        assertThat(blocks).extracting(block -> block.get("text"))
                .containsExactly("第十二页原文", "第十一页原文", "第十三页原文", "第八页原文", "第十六页原文");
        assertThatThrownBy(() -> controller.handoutDocumentPageRead(workerKey,
                new HandoutDocumentPageReadRequest(runId, ref, 13, 0, 8, 4_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.handoutDocumentPageRead(workerKey,
                new HandoutDocumentPageReadRequest(runId, ref, 12, 5, 8, 4_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void teacherSearchPersistsOpaqueDocumentAuthorizationBeforeBoundedRead() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-planned-resource-001";
        String documentId = "teacher-document-001";
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                documentId, "tenant", "teacher-1", "feishu", "函数资料.docx", null, null,
                "TEACHER_PRIVATE", "synced", "parsed", "ready", "ready", "md", List.of(), "TEXT"));
        blockStore.replaceActiveBlocks("tenant", documentId, List.of(new TeacherDocumentBlockResponse(
                "teacher-block-001", documentId, "lesson.md", "markdown", 0, "函数", "最小值", 1, null,
                "", "reference", "配方法可以确定二次函数的最小值。" + "![](IMAJES/image-001.jpg)",
                "配方法可以确定二次函数的最小值。" + "![](IMAJES/image-001.jpg)",
                "[{\"markdownLine\":\"![](IMAJES/image-001.jpg)\",\"logicalPath\":\"函数/最值/IMAJES/image-001.jpg\"}]",
                "[]", "[]", "[]", "checksum", 1.0d, "active")));
        TeacherResourceBlockSearchService searchService = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);
        Path sourceRoot = tempDir.resolve("staging").resolve("teacher-source");
        Files.createDirectories(sourceRoot.resolve("IMAJES"));
        Files.writeString(sourceRoot.resolve("lesson.md"),
                "配方法可以确定二次函数的最小值。![](IMAJES/image-001.jpg)", StandardCharsets.UTF_8);
        Files.write(sourceRoot.resolve("IMAJES/image-001.jpg"), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        TeacherSourceFileReader sourceReader = new TeacherSourceFileReader(new TeacherSourceSyncProperties(
                "", tempDir.resolve("download.py"), tempDir.resolve("appkey"), tempDir.resolve("staging"),
                tempDir.resolve("assets"), 1, 30));
        sourceReader.register("tenant", documentId, sourceRoot, "checksum");
        searchService.setSourceFileReader(sourceReader);
        InMemoryTaskStore taskStore = new InMemoryTaskStore(task(runId, List.of()));
        AgentToolBrokerController controller = new AgentToolBrokerController(
                searchService, null, new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, taskStore, null);

        Map<String, Object> search = controller.handoutTeacherResourceSearch(workerKey,
                new HandoutTeacherResourceSearchRequest(runId, "二次函数 配方法 最小值", 6));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) search.get("items");
        assertThat(items).singleElement().satisfies(item -> {
            String excerpt = String.valueOf(item.get("excerpt"));
            assertThat(item).containsEntry("ref", evidenceRef(workerKey, runId, taskStore.findByTaskId(runId)
                            .orElseThrow().evidence().getFirst()))
                    .containsEntry("documentRef", documentRef(workerKey, runId, documentId));
            assertThat(excerpt).contains("配方法可以确定二次函数的最小值。", "![source-image:", "](IMAJES/image-001.jpg)")
                    .doesNotContain("asset-", documentId, "函数/最值", "![](IMAJES/image-001.jpg)");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> imageRefs = (List<Map<String, Object>>) item.get("imageRefs");
            assertThat(imageRefs).containsExactly(Map.of(
                    "markdownLine", String.valueOf(imageRefs.getFirst().get("markdownLine")),
                    "logicalPath", "函数/最值/IMAJES/image-001.jpg"));
            assertThat(String.valueOf(imageRefs.getFirst().get("markdownLine")))
                    .startsWith("![source-image:").endsWith("](IMAJES/image-001.jpg)");
            assertThat(item).doesNotContainKeys("sourcePath", "sourceUrl", "query", "collection", "base64");
        });
        assertThat(taskStore.findByTaskId(runId).orElseThrow().evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.sourceScope()).isEqualTo("TEACHER_RESOURCE");
            assertThat(evidence.sourceDocumentId()).isEqualTo(documentId);
            assertThat(evidence.chunkId()).isEqualTo("teacher-block-001");
            assertThat(evidence.sourcePath()).isBlank();
            assertThat(evidence.sourceUrl()).isBlank();
            assertThat(evidence.imageRefs()).containsExactly(Map.of(
                    "markdownLine", "![](IMAJES/image-001.jpg)",
                    "logicalPath", "函数/最值/IMAJES/image-001.jpg"));
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contextItems = (List<Map<String, Object>>) controller.handoutContext(workerKey,
                new HandoutContextRequest(runId, List.of(String.valueOf(items.getFirst().get("ref"))), 6)).get("items");
        assertThat(contextItems).singleElement().satisfies(item ->
                assertThat(item).containsEntry("documentRef", documentRef(workerKey, runId, documentId)));

        Map<String, Object> read = controller.handoutDocumentRead(workerKey, new HandoutDocumentReadRequest(
                runId, documentRef(workerKey, runId, documentId), 80, 4_000));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) read.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(String.valueOf(blocks.getFirst().get("text")))
                .contains("配方法可以确定二次函数的最小值。", "![source-image:", "](IMAJES/image-001.jpg)")
                .doesNotContain(documentId, "函数/最值", "asset-");
        assertThatThrownBy(() -> controller.handoutDocumentRead(workerKey, new HandoutDocumentReadRequest(
                runId, documentRef(workerKey, runId, "foreign-document"), 80, 4_000)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void handoutContextRewritesBoundMarkdownImagesForPythonInSourceOrder() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-context-images-001";
        String documentId = "teacher-images-001";
        String first = "![](IMAJES/image-001.jpg)";
        String second = "![](IMAJES/image-002.png)";
        TeachingEvidence evidence = new TeachingEvidence(
                "TEACHER_RESOURCE", "图形资料.md", "block-images", 1,
                "图像定义。" + first + "图像结论。" + second,
                "", "", documentId, "feishu", "", "", List.of(), "", List.of(
                        Map.of("markdownLine", first, "logicalPath", "图形资料/IMAJES/image-001.jpg"),
                        Map.of("markdownLine", second, "logicalPath", "图形资料/IMAJES/image-002.png")));
        AgentToolBrokerController controller = new AgentToolBrokerController(
                null, null, new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, new InMemoryTaskStore(task(runId, List.of(evidence))), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) controller.handoutContext(workerKey,
                new HandoutContextRequest(runId, List.of(evidenceRef(workerKey, runId, evidence)), 12)).get("items");

        assertThat(items).singleElement().satisfies(item -> {
            String excerpt = String.valueOf(item.get("excerpt"));
            assertThat(excerpt).contains("图像定义。", "图像结论。")
                    .contains("![source-image:")
                    .contains("](IMAJES/image-001.jpg)", "](IMAJES/image-002.png)")
                    .doesNotContain("asset-", documentId, "![](IMAJES/image-001.jpg)", "![](IMAJES/image-002.png)");
            assertThat(excerpt.indexOf("image-001.jpg)")).isLessThan(excerpt.indexOf("image-002.png)"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> imageRefs = (List<Map<String, Object>>) item.get("imageRefs");
            assertThat(imageRefs).hasSize(2);
            assertThat(String.valueOf(imageRefs.getFirst().get("markdownLine")))
                    .startsWith("![source-image:").endsWith("](IMAJES/image-001.jpg)");
            assertThat(imageRefs.getFirst()).containsEntry("logicalPath", "图形资料/IMAJES/image-001.jpg");
            assertThat(String.valueOf(imageRefs.get(1).get("markdownLine")))
                    .startsWith("![source-image:").endsWith("](IMAJES/image-002.png)");
            assertThat(imageRefs.get(1)).containsEntry("logicalPath", "图形资料/IMAJES/image-002.png");
        });
    }
    @Test
    void physicalFileEvidenceRewritesBoundMarkdownImageWhenRootIdDiffers() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-physical-file-image-001";
        String rootDocumentId = "teacher-root-001";
        String fileDocumentId = "teacher-file-001";
        String markdownImage = "![](IMAJES/image-001.jpg)";
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                fileDocumentId, "tenant", "teacher-1", "feishu", "抛物线资料.md", null, null,
                "TEACHER_PRIVATE", "synced", "parsed", "ready", "ready", "md", List.of(), "TEXT"));
        blockStore.replaceActiveBlocks("tenant", fileDocumentId, List.of(new TeacherDocumentBlockResponse(
                "image-block-001", fileDocumentId, "image-block-001", "markdown", 0, "", "", 1, null,
                "", "reference", "图像定义。" + markdownImage, "图像定义。" + markdownImage,
                "[{\"markdownLine\":\"![](IMAJES/image-001.jpg)\",\"logicalPath\":\"抛物线/IMAJES/image-001.jpg\"}]",
                "[]", "[]", "[]", "checksum", 1.0d, "active")));
        TeacherResourceBlockSearchService searchService = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);
        Path sourceRoot = tempDir.resolve("staging").resolve("teacher-source");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("抛物线资料.md"), "图像定义。" + markdownImage, StandardCharsets.UTF_8);
        TeacherSourceFileReader sourceReader = new TeacherSourceFileReader(new TeacherSourceSyncProperties(
                "", tempDir.resolve("download.py"), tempDir.resolve("appkey"), tempDir.resolve("staging"),
                tempDir.resolve("assets"), 1, 30));
        sourceReader.register("tenant", fileDocumentId, sourceRoot, "checksum");
        searchService.setSourceFileReader(sourceReader);
        TeachingEvidence evidence = new TeachingEvidence(
                "TEACHER_RESOURCE", "抛物线资料.md", "image-block-001", 1,
                "图像定义。" + markdownImage, "", "", fileDocumentId, "feishu", "", "", List.of());
        AgentToolBrokerController controller = new AgentToolBrokerController(
                searchService, null, new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, new InMemoryTaskStore(task(runId, List.of(evidence))), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) controller.handoutContext(workerKey,
                new HandoutContextRequest(runId, List.of(evidenceRef(workerKey, runId, evidence)), 12)).get("items");

        assertThat(items).singleElement().satisfies(item -> {
            String excerpt = String.valueOf(item.get("excerpt"));
            assertThat(excerpt).contains("![source-image:", "](IMAJES/image-001.jpg)")
                    .doesNotContain(rootDocumentId, fileDocumentId, "抛物线/IMAJES", markdownImage, "asset-");
        });
    }

    @Test
    void handoutDocumentReadSkipsStaleImageBlockAndKeepsAuthorizedSibling() throws Exception {
        String workerKey = "worker-secret";
        String runId = "run-stale-image-sibling-001";
        String documentId = "teacher-stale-image-001";
        String staleImage = "![](IMAJES/stale.png)";
        String validImage = "![](IMAJES/valid.png)";
        String validLogicalPath = "抛物线/IMAJES/valid.png";
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                documentId, "tenant", "teacher-1", "feishu", "抛物线资料.md", null, null,
                "TEACHER_PRIVATE", "synced", "parsed", "ready", "ready", "md", List.of(), "TEXT");
        resourceStore.save(document);
        blockStore.replaceActiveBlocks("tenant", documentId, List.of(
                new TeacherDocumentBlockResponse("stale-block", documentId, "stale-block", "markdown", 0,
                        "", "", 1, null, "", "reference", "失效图。" + staleImage,
                        "失效图。" + staleImage,
                        "[{\"markdownLine\":\"![](IMAJES/stale.png)\",\"logicalPath\":\"抛物线/IMAJES/stale.png\"}]",
                        "[]", "[]", "[]", "checksum", 1.0d, "active"),
                new TeacherDocumentBlockResponse("valid-block", documentId, "valid-block", "markdown", 1,
                        "", "", 2, null, "", "reference", "有效图。" + validImage,
                        "有效图。" + validImage,
                        "[{\"markdownLine\":\"![](IMAJES/valid.png)\",\"logicalPath\":\"抛物线/IMAJES/valid.png\"}]",
                        "[]", "[]", "[]", "checksum", 1.0d, "active")));
        TeacherResourceAssetService assetService = new TeacherResourceAssetService(assetStore, resourceStore,
                new TeacherSourceSyncProperties("", tempDir.resolve("download.py"), tempDir.resolve("appkey"),
                        tempDir.resolve("staging"), tempDir.resolve("assets"), 1, 30));
        TeacherResourceAssetResponse asset = assetService.saveExtractedAsset(document, validLogicalPath, null,
                "IMAJES/valid.png", validPngBytes(), "image/png").orElseThrow();
        assertThat(asset.assetId()).isNotBlank();
        TeacherResourceBlockSearchService searchService = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);
        TeachingEvidence evidence = new TeachingEvidence("TEACHER_RESOURCE", "抛物线资料.md", "valid-block", 2,
                "有效图。" + validImage, "", "", documentId, "feishu", "", "", List.of());
        AgentToolBrokerController controller = new AgentToolBrokerController(searchService, assetService,
                new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey), null,
                new InMemoryTaskStore(task(runId, List.of(evidence))), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) controller.handoutDocumentRead(workerKey,
                new HandoutDocumentReadRequest(runId, documentRef(workerKey, runId, documentId), 80, 4_000)).get("blocks");

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(String.valueOf(block.get("text"))).contains("有效图。", "![source-image:", "](IMAJES/valid.png)")
                    .doesNotContain("失效图。", "stale.png", "抛物线/IMAJES");
            assertThat(block.get("imageRefs")).asList().singleElement().satisfies(imageRef ->
                    assertThat(String.valueOf(((Map<?, ?>) imageRef).get("markdownLine")))
                            .startsWith("![source-image:").endsWith("](IMAJES/valid.png)"));
        });
    }

    @Test
    void zeroInitialEvidenceReturnsEmptyRunBoundContextForPythonPlanning() {
        String workerKey = "worker-secret";
        String runId = "run-zero-initial-evidence-001";
        AgentToolBrokerController controller = new AgentToolBrokerController(
                null, null, new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", workerKey),
                null, new InMemoryTaskStore(task(runId, List.of())), null);

        Map<String, Object> response = controller.handoutContext(workerKey,
                new HandoutContextRequest(runId, List.of(), 12));

        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(response.get("items")).isEqualTo(List.of());
    }

    @Test
    void handoutContextResolvesOnlyCurrentRunEvidenceAndRejectsForeignReference() throws Exception {
        TeachingEvidence selected = new TeachingEvidence(
                "TEACHER_RESOURCE", "当前运行完整资料.docx", "approved-block", 2,
                "当前运行已选中的证据正文。", "", "", "approved-document", "feishu", "", "", List.of("asset-approved"));
        TeachingEvidence foreign = new TeachingEvidence(
                "TEACHER_RESOURCE", "不应出现的全局匹配资料.docx", "foreign-block", 3,
                "不得进入当前运行的资料。", "", "", "foreign-document", "feishu", "", "", List.of());
        TeachingEvidence textbookMergedIntoTeacherResults = new TeachingEvidence(
                "TEACHER_RESOURCE", "教材混合检索结果", "textbook-block", 4,
                "教材摘要仍可见，但不能走教师文件读取。", "", "", "textbook-document", "public_textbook", "", "", List.of());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.agent-worker.shared-key", "worker-secret");
        AgentToolBrokerController controller = new AgentToolBrokerController(
                null, null, environment, null, store(task("run-authorized-001", List.of(selected, textbookMergedIntoTeacherResults)), task("run-foreign-001", List.of(foreign))), null);
        String selectedRef = evidenceRef("worker-secret", "run-authorized-001", selected);
        String foreignRef = evidenceRef("worker-secret", "run-foreign-001", foreign);

        String textbookRef = evidenceRef("worker-secret", "run-authorized-001", textbookMergedIntoTeacherResults);
        var response = controller.handoutContext("worker-secret", new HandoutContextRequest(
                "run-authorized-001", List.of(selectedRef, textbookRef), 12));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>) response.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).containsEntry("ref", selectedRef)
                .containsEntry("documentName", "当前运行完整资料.docx")
                .containsEntry("documentRef", documentRef("worker-secret", "run-authorized-001", selected.sourceDocumentId()))
                .doesNotContainValue("不应出现的全局匹配资料.docx");
        assertThat(items.get(1)).containsEntry("ref", textbookRef).containsEntry("documentRef", "");
        assertThatThrownBy(() -> controller.handoutContext("worker-secret", new HandoutContextRequest(
                "run-authorized-001", List.of(foreignRef), 12)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.handoutContext("worker-secret", new HandoutContextRequest(
                "run-authorized-001", List.of("PUBLIC_TEXTBOOK:global:block"), 12)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static final class InMemoryTaskStore implements TeachingTaskStore {
        private final java.util.Map<String, TeachingTaskResponse> tasks = new java.util.concurrent.ConcurrentHashMap<>();

        private InMemoryTaskStore(TeachingTaskResponse... initialTasks) {
            for (TeachingTaskResponse task : initialTasks) {
                tasks.put(task.taskId(), task);
            }
        }

        @Override public Optional<TeachingTaskResponse> findByIdempotencyKey(String idempotencyKey) { return Optional.empty(); }
        @Override public Optional<TeachingTaskResponse> findByTaskIdAndOwnerKey(String taskId, String ownerKey) {
            return findByTaskId(taskId);
        }
        @Override public Optional<TeachingTaskResponse> findByTaskId(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }
        @Override public List<TeachingTaskResponse> listRecentByOwnerKey(String ownerKey, int limit) { return List.of(); }
        @Override public TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
            tasks.put(task.taskId(), task);
            return task;
        }
    }

    private static TeachingTaskStore store(TeachingTaskResponse... tasks) {
        return new TeachingTaskStore() {
            @Override public Optional<TeachingTaskResponse> findByIdempotencyKey(String idempotencyKey) { return Optional.empty(); }
            @Override public Optional<TeachingTaskResponse> findByTaskIdAndOwnerKey(String taskId, String ownerKey) { return Optional.empty(); }
            @Override public Optional<TeachingTaskResponse> findByTaskId(String taskId) {
                return java.util.Arrays.stream(tasks).filter(task -> task.taskId().equals(taskId)).findFirst();
            }
            @Override public List<TeachingTaskResponse> listRecentByOwnerKey(String ownerKey, int limit) { return List.of(); }
            @Override public TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task) { return task; }
        };
    }

    private static TeachingTaskResponse task(String taskId, List<TeachingEvidence> evidence) {
        return new TeachingTaskResponse(taskId, "request", "tenant", "teacher", "teacher-1", TeachingTaskStatus.RUNNING,
                "题目", "目标", List.of(), List.of(), evidence, "", "", "", List.of(),
                new TeachingTaskResponse.MemoryReuse(false, null, "private", "", 0D, ""), List.of(), null, "");
    }

    private static byte[] validPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        image.setRGB(1, 1, 0x00FF00);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String evidenceRef(String secret, String runId, TeachingEvidence evidence) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((secret + "|" + runId + "|evidence|"
                + evidence.sourceDocumentId() + "|" + evidence.sourceScope() + "|" + evidence.sourceTitle() + "|"
                + evidence.chunkId() + "|assets=" + (evidence.assetIds() == null ? "" : evidence.assetIds().stream()
                        .sorted().collect(java.util.stream.Collectors.joining(",")))).getBytes(StandardCharsets.UTF_8));
        return "ev_" + HexFormat.of().formatHex(digest, 0, 16);
    }

    private static String documentRef(String secret, String runId, String documentId) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((secret + "|" + runId + "|document|" + documentId)
                .getBytes(StandardCharsets.UTF_8));
        return "doc_" + HexFormat.of().formatHex(digest, 0, 16);
    }
}
