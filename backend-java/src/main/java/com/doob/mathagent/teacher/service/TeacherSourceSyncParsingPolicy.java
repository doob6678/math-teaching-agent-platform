package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.feishu.FeishuCredential;
import com.doob.mathagent.feishu.FeishuCredentialService;
import com.doob.mathagent.feishu.FeishuResourceBindingService;
import com.doob.mathagent.teacher.formula.OmmlFormulaExtractor;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionProperties;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.VectorIndexSyncException;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.ParsedBlock;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.ImageReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.PendingAsset;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.StoredAssetReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.FormulaReference;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.FormulaVisionBudget;
import static com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService.*;

/**
 * Deterministic file parsing, rendering, and checksum policy extracted from source synchronization.
 * The execution facade remains responsible for IO sequencing and provider calls.
 */
final class TeacherSourceSyncParsingPolicy {
    private TeacherSourceSyncParsingPolicy() {
        // Stateless policy component.
    }


    /**
     * Renders one PDF page through the installed native Poppler renderer when it is available.
     *
     * <p>Some scanned courseware stores very large page photographs. Poppler preserves the page's color raster while
     * avoiding PDFBox's slow Java2D pixel transforms; PDFBox remains the deterministic fallback for deployments that
     * do not install the native executable.</p>
     */
    static byte[] renderPdfPageAsPng(Path pdf, int pageNo, PDFRenderer fallbackRenderer) throws IOException {
        Optional<byte[]> nativeImage = tryRenderPdfPageWithNativeRenderer(pdf, pageNo);
        if (nativeImage.isPresent()) {
            return nativeImage.get();
        }
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(fallbackRenderer.renderImageWithDPI(pageNo - 1, pdfPageRenderDpi()), "png", imageBytes);
        return imageBytes.toByteArray();
    }


    /** Attempts one isolated Poppler render and cleans all temporary files irrespective of renderer success. */
    static Optional<byte[]> tryRenderPdfPageWithNativeRenderer(Path pdf, int pageNo) {
        Path temporaryDirectory = null;
        try {
            temporaryDirectory = Files.createTempDirectory("math-agent-pdf-page-");
            Path outputStem = temporaryDirectory.resolve("page");
            String rendererExecutable = textOrDefault(System.getenv(NATIVE_PDF_RENDERER_ENV), NATIVE_PDF_RENDERER);
            Process process = new ProcessBuilder(
                    rendererExecutable,
                    "-png",
                    "-singlefile",
                    "-f", String.valueOf(pageNo),
                    "-l", String.valueOf(pageNo),
                    "-r", String.valueOf(pdfPageRenderDpi()),
                    pdf.toAbsolutePath().toString(),
                    outputStem.toString())
                    // Rendering has no caller-visible diagnostics. Discard it so the timeout remains enforceable even
                    // when a native PDF reports verbose font warnings on stderr.
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(NATIVE_PDF_RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            Path output = temporaryDirectory.resolve("page.png");
            return Files.isRegularFile(output) ? Optional.of(Files.readAllBytes(output)) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return Optional.empty();
        } finally {
            if (temporaryDirectory != null) {
                try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Temporary native-render outputs never participate in source indexing.
                        }
                    });
                } catch (IOException ignored) {
                    // The regular PDFBox fallback remains safe even if Windows releases a temp handle late.
                }
            }
        }
    }


    /** Reads an operator-selected indexing DPI while retaining the high-fidelity default for normal deployments. */
    static int pdfPageRenderDpi() {
        String configured = textOrDefault(System.getenv(PDF_PAGE_RENDER_DPI_ENV), "");
        if (configured.isBlank()) {
            return PDF_PAGE_RENDER_DPI;
        }
        try {
            int dpi = Integer.parseInt(configured);
            return dpi > 0 ? dpi : PDF_PAGE_RENDER_DPI;
        } catch (NumberFormatException exception) {
            return PDF_PAGE_RENDER_DPI;
        }
    }


    /**
     * Lists supported local Markdown and plain-text files.
     */
    static List<Path> listSupportedFiles(Path root) {
        if (Files.isRegularFile(root)) {
            return isSupportedFile(root) ? List.of(root) : List.of();
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(TeacherSourceSyncExecutionService::isSupportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to scan local resource path: " + root, exception);
        }
    }


    /**
     * Checks whether the file can be parsed by the current local sync parser set.
     */
    static boolean isSupportedFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".md")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".docx")
                || fileName.endsWith(".pdf");
    }


    /**
     * Reads a UTF-8 source file.
     */
    static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read local resource file: " + file, exception);
        }
    }


    /**

     * Parses a supported source file into normalized text blocks.
     */
    static List<ParsedBlock> parseFileBlocks(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            return parseTextBlocks(readUtf8(file), file);
        }
        if (fileName.endsWith(".docx")) {
            return parseDocxBlocks(file);
        }
        if (fileName.endsWith(".pdf")) {
            return parsePdfBlocks(file);
        }
        return List.of();
    }


    /**
     * Parses Markdown/text into chapter/section-aware text blocks.
     */
    static List<ParsedBlock> parseTextBlocks(String text, Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        String section = null;
        Integer pageNo = null;
        StringBuilder current = new StringBuilder();
        List<PendingAsset> currentAssets = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) {
                flushBlock(blocks, chapter, section, pageNo, current, currentAssets);
                chapter = stripped.substring(2).strip();
                section = null;
                pageNo = null;
                continue;
            }
            if (stripped.startsWith("## ")) {
                flushBlock(blocks, chapter, section, pageNo, current, currentAssets);
                section = stripped.substring(3).strip();
                pageNo = pageNumberFromSection(section);
                continue;
            }
            if (stripped.startsWith("### ")) {
                // A third-level Markdown heading commonly starts a concrete source question (for example “2013 年
                // 涂色问题”).  It must own its following answer, rather than being fused with later variations under
                // the same H2 topic; otherwise RAG can select one question's image but another question's answer.
                flushBlock(blocks, chapter, section, pageNo, current, currentAssets);
                section = stripped.substring(4).strip();
                pageNo = pageNumberFromSection(section);
                continue;
            }
            ImageReference image = markdownImageReference(stripped);
            if (image != null) {
                readMarkdownAsset(file, image.path()).ifPresent(currentAssets::add);
                // Keep alt text as retrieval evidence, but never persist a signed Feishu URL in a searchable block.
                if (!image.altText().isBlank()) {
                    current.append(image.altText()).append('\n');
                }
                continue;
            }
            if (!stripped.isBlank()) {
                if (!currentAssets.isEmpty()) {
                    /*
                     * The preceding text plus its immediately following image is a complete visual source unit.
                     * Start the next explanation in a new block: otherwise a later “6 种颜色” note remains in the
                     * same searchable block as the original 4-colour map and the renderer cannot prove which
                     * condition owns that image.
                     */
                    flushBlock(blocks, chapter, section, pageNo, current, currentAssets);
                }
                current.append(stripped).append('\n');
            }
        }
        flushBlock(blocks, chapter, section, pageNo, current, currentAssets);
        return blocks;
    }


    /**
     * Appends a non-empty parsed block and clears the text/asset buffers together so Markdown image refs stay bound
     * to the paragraph that introduced them.
     */
    static void flushBlock(
            List<ParsedBlock> blocks,
            String chapter,
            String section,
            Integer pageNo,
            StringBuilder current,
            List<PendingAsset> currentAssets) {
        String value = current.toString().strip();
        if (!value.isBlank() || !currentAssets.isEmpty()) {
            String text = value.isBlank() ? "[Markdown image block; no extractable text]" : value;
            blocks.add(new ParsedBlock(chapter, section, pageNo, text, List.copyOf(currentAssets), List.of()));
        }
        current.setLength(0);
        currentAssets.clear();
    }


    /** Extracts a page number only from explicit Markdown page sections, never from arbitrary lesson text. */
    static Integer pageNumberFromSection(String section) {
        Matcher matcher = PAGE_SECTION_PATTERN.matcher(textOrDefault(section, ""));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }


    /** Finds a Markdown or HTML image whose target has already been materialized by the Feishu worker. */
    static ImageReference markdownImageReference(String line) {
        Matcher markdown = MARKDOWN_IMAGE_PATTERN.matcher(line);
        if (markdown.find()) {
            return new ImageReference(
                    textOrDefault(markdown.group(1), ""),
                    decodeLocalImagePath(textOrDefault(markdown.group(2), textOrDefault(markdown.group(3), ""))));
        }
        Matcher html = HTML_IMAGE_TAG_PATTERN.matcher(line);
        if (html.find()) {
            String tag = html.group();
            String href = htmlAttributeValue(HTML_IMAGE_HREF_PATTERN, tag);
            String src = htmlAttributeValue(HTML_IMAGE_SRC_PATTERN, tag);
            return new ImageReference(
                    "",
                    decodeLocalImagePath(firstNonBlank(href, src)));
        }
        return null;
    }


    /** Reads one image attribute without allowing a later provider token to override a local href. */
    static String htmlAttributeValue(Pattern attributePattern, String tag) {
        Matcher matcher = attributePattern.matcher(textOrDefault(tag, ""));
        if (!matcher.find()) {
            return "";
        }
        return textOrDefault(matcher.group(1), textOrDefault(matcher.group(2), textOrDefault(matcher.group(3), "")));
    }


    /** Reads a local image only when it remains under the exported document's parent directory. */
    static Optional<PendingAsset> readMarkdownAsset(Path markdownFile, String imagePath) {
        String normalizedPath = textOrDefault(imagePath, "");
        if (normalizedPath.isBlank()
                || normalizedPath.startsWith("http://")
                || normalizedPath.startsWith("https://")
                || normalizedPath.startsWith("data:")) {
            return Optional.empty();
        }
        Path parent = markdownFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return Optional.empty();
        }
        Path target = parent.resolve(normalizedPath).normalize();
        if (!target.startsWith(parent) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            byte[] content = Files.readAllBytes(target);
            if (content.length == 0) {
                return Optional.empty();
            }
            String mimeType = Files.probeContentType(target);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = mimeTypeFromName(target.getFileName().toString());
            }
            return Optional.of(new PendingAsset(
                    normalizedPath.replace('\\', '/'),
                    content,

                    textOrDefault(mimeType, "application/octet-stream")));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }


    static String decodeLocalImagePath(String path) {
        String value = textOrDefault(path, "");
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }


    static String mimeTypeFromName(String fileName) {
        String normalized = textOrDefault(fileName, "").toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (normalized.endsWith(".gif")) {
            return "image/gif";
        }
        if (normalized.endsWith(".webp")) {
            return "image/webp";
        }
        if (normalized.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }


    /**
     * Parses DOCX paragraphs and extracts embedded images without relying on filenames or keywords for classification.
     */
    static List<ParsedBlock> parseDocxBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        configurePoiDocxEntryLimit();
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                List<PendingAsset> assets = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (XWPFRun run : paragraph.getRuns()) {

                    String runText = textOrDefault(run.text(), "");
                    if (!runText.isBlank()) {
                        text.append(runText).append(' ');
                    }
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        XWPFPictureData pictureData = picture.getPictureData();
                        if (pictureData == null) {
                            continue;
                        }
                        String providerId = pictureData.getPackagePart().getPartName().getName();
                        String mimeType = textOrDefault(pictureData.getPackagePart().getContentType(), "application/octet-stream");
                        byte[] data = pictureData.getData();
                        if (data == null || data.length == 0) {
                            data = pictureData.getPackagePart().getInputStream().readAllBytes();
                        }
                        if (data == null || data.length == 0) {
                            /*
                             * Some real DOCX files produced by python-docx expose the drawing relationship through
                             * POI but return an empty PackagePart stream. The binary still exists in word/media/*, so
                             * fall back to the package entry instead of dropping imageRefs and losing the asset.
                             */
                            data = readDocxPackagePart(file, providerId);
                        }
                        assets.add(new PendingAsset(providerId, data, mimeType));
                    }
                }
                String paragraphText = textOrDefault(text.toString(), paragraph.getText());
                /*
                 * XWPFRun.text() intentionally does not flatten Word's m:oMath tree. Extract OMML from the same
                 * paragraph before persistence so equations do not become invisible blank gaps in an otherwise valid
                 * DOCX question. The original XML remains in formula_refs for lossless rendering/reprocessing.
                 */
                List<OmmlFormulaExtractor.ExtractedFormula> formulas =
                        OmmlFormulaExtractor.extractFromParagraphXml(paragraph.getCTP().xmlText());
                if (!paragraphText.isBlank() || !assets.isEmpty() || !formulas.isEmpty()) {
                    blocks.add(new ParsedBlock(
                            chapter,
                            null,
                            null,
                            paragraphText.isBlank() && formulas.isEmpty()
                                    ? "[DOCX image block; no extractable text]"
                                    : paragraphText,
                            List.copyOf(assets),
                            formulas));
                }
            }
        } catch (IOException exception) {
            // A top-level “parse failed” status without the native DOCX/ZIP reason forces operators to guess whether
            // the source is corrupt, the path is wrong, or POI rejected a particular Word package. Keep the filename
            // and the bounded exception message in the resumable job record; neither contains model prompts or
            // teacher content, but it makes a real source failure actionable.
            String reason = textOrDefault(exception.getMessage(), exception.getClass().getSimpleName());
            throw new IllegalArgumentException("Failed to parse DOCX resource file: " + file + "; reason: " + reason, exception);
        }
        return blocks;
    }


    /**
     * Raises only POI's package-entry count for trusted, permission-checked local exam resources. The 2024 source
     * contains 1,489 legitimate equation/image entries; compressed-size and zip-bomb protections remain owned by
     * POI, while this independently configurable ceiling avoids rejecting a valid paper as malicious.
     */
    static synchronized void configurePoiDocxEntryLimit() {
        int configured = Integer.getInteger(DOCX_MAX_ZIP_ENTRIES_PROPERTY, DEFAULT_DOCX_MAX_ZIP_ENTRIES);
        ZipSecureFile.setMaxFileCount(Math.max(1, configured));
    }


    static byte[] readDocxPackagePart(Path file, String providerId) throws IOException {
        String entryName = textOrDefault(providerId, "").replace('\\', '/');
        if (entryName.startsWith("/")) {
            entryName = entryName.substring(1);
        }
        if (entryName.isBlank()) {
            return new byte[0];
        }
        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return new byte[0];
            }
            return zipFile.getInputStream(entry).readAllBytes();
        }
    }


    /**
     * Parses a PDF into one block per page when extractable text exists.
     */
    static List<ParsedBlock> parsePdfBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 1; page <= document.getNumberOfPages(); page += 1) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textOrDefault(stripper.getText(document), "");
                List<PendingAsset> assets = new ArrayList<>();
                ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
                imageBytes.write(renderPdfPageAsPng(file, page, renderer));
                assets.add(new PendingAsset("pdf-page:" + page, imageBytes.toByteArray(), "image/png"));
                if (!text.isBlank() || !assets.isEmpty()) {
                    blocks.add(new ParsedBlock(
                            chapter,
                            null,
                            page,
                            text.isBlank() ? "[PDF page image; no extractable text]" : text,
                            List.copyOf(assets),
                            List.of()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse PDF resource file: " + file, exception);
        }
        return blocks;
    }


    /** Renders an AI-mode DOCX locally through Word, producing page images used by the shared two/four-page pipeline. */
    static List<ParsedBlock> parseRenderedDocxPages(Path docx) {
        Path renderedPdf = null;
        try {
            renderedPdf = Files.createTempFile("math-agent-docx-pages-", ".pdf");
            Path script = resolveDocxRenderScript();
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(), "-SourcePath", docx.toString(), "-TargetPath", renderedPdf.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.isRegularFile(renderedPdf)) {
                return List.of();
            }
            return parsePdfBlocks(renderedPdf);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        } finally {
            if (renderedPdf != null) {
                try { Files.deleteIfExists(renderedPdf); } catch (IOException ignored) { }
            }
        }
    }


    static Path resolveDocxRenderScript() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path candidate : List.of(
                cwd.resolve("scripts/local/render-docx-to-pdf.ps1"),
                cwd.resolve("../scripts/local/render-docx-to-pdf.ps1"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("DOCX page renderer script is unavailable");
    }


    /**
     * Keeps a stable source-side block key for incremental sync. The MySQL auto id may change when rows are inserted,
     * but this external id must stay derivable from the real source structure so updates can reconcile in place.
     */
    static String stableExternalBlockId(String sourcePath, ParsedBlock parsed, int order) {
        String chapter = normalizeHeading(parsed.chapter());
        String section = normalizeHeading(parsed.section());
        String page = parsed.pageNo() == null ? "0" : String.valueOf(parsed.pageNo());
        return sourcePath + "|" + page + "|" + chapter + "|" + section + "|" + order;
    }


    /**
     * Classifies one parsed block into a small set of generic roles used by stage-two in-document rerank. Keep this
     * broad and source-driven; do not inject benchmark keywords or per-dataset rules here.
     */
    static String classifyBlockRole(String sourcePath, ParsedBlock parsed, String normalizedText) {
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(sourcePath, ""),
                textOrDefault(parsed.chapter(), ""),
                textOrDefault(parsed.section(), ""),
                textOrDefault(normalizedText, ""))).toLowerCase(Locale.ROOT);
        if (containsAny(haystack, "答案", "解析", "讲评", "点评", "评注", "解答", "solution", "analysis")) {
            return "analysis";
        }
        if (containsAny(haystack, "方法", "讲法", "思路", "策略", "套路", "model", "method")) {
            return "method";
        }
        if (containsAny(haystack, "板书", "板演", "blackboard")) {
            return "boardwork";
        }
        if (containsAny(haystack, "模板", "讲义模板", "template")) {
            return "template";
        }
        if (containsAny(haystack, "提示", "提醒", "易错", "注意", "tip")) {
            return "tip";
        }
        if (containsAny(haystack, "真题", "模拟", "试题", "题目", "例题", "question", "exam")) {
            return "question";
        }
        if (containsAny(haystack, "专题", "讲义", "课堂", "notes", "lesson")) {
            return "lesson";
        }
        return "reference";
    }


    /**
     * Normalizes text for retrieval and checksum stability.
     */
    static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }


    static String normalizeHeading(String value) {
        return normalizeText(textOrDefault(value, ""))
                .replace('|', '/')
                .replace('#', '/');
    }


    static String jsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize graph alignment metadata", exception);
        }
    }


    static String imageRefs(List<StoredAssetReference> assets) {
        if (assets == null || assets.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (StoredAssetReference asset : assets) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("assetId", asset.assetId());
            ref.put("pageNo", asset.pageNo());
            ref.put("sourcePath", asset.sourcePath());
            ref.put("mimeType", asset.mimeType());
            refs.add(ref);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(refs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize teacher resource imageRefs", exception);
        }
    }


    /**
     * Serializes native DOCX equations into the existing JSON column rather than adding a parallel formula table.
     *
     * <p>{@code omml} is lossless source, {@code mathMl} is renderer-friendly structure, and {@code plainText} is
     * the only retrieval evidence. No LaTex is fabricated when a verified converter is unavailable.</p>
     */
    static List<FormulaReference> formulaReferences(List<OmmlFormulaExtractor.ExtractedFormula> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return new ArrayList<>();
        }
        List<FormulaReference> refs = new ArrayList<>();
        for (OmmlFormulaExtractor.ExtractedFormula formula : formulas) {
            refs.add(new FormulaReference(
                    "docx_omml",
                    "verified_native",
                    1.0d,
                    formula.omml(),
                    formula.mathMl(),
                    formula.latex(),
                    formula.plainText(),
                    null,
                    null));
        }
        return refs;
    }


    static List<FormulaReference> bindFormulaAssets(
            List<FormulaReference> formulas,
            List<StoredAssetReference> assets) {
        if (formulas == null || formulas.isEmpty() || assets == null || assets.isEmpty()) {
            return formulas == null ? List.of() : formulas;
        }
        String assetId = assets.getFirst().assetId();
        return formulas.stream().map(formula -> new FormulaReference(
                formula.source(), formula.recognitionStatus(), formula.confidence(), formula.omml(), formula.mathMl(),
                formula.latex(), formula.plainText(), assetId, formula.model())).toList();
    }


    static String formulaRefs(List<FormulaReference> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (FormulaReference formula : formulas) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("source", formula.source());
            ref.put("recognitionStatus", formula.recognitionStatus());
            ref.put("confidence", formula.confidence());
            putIfPresent(ref, "omml", formula.omml());
            putIfPresent(ref, "mathMl", formula.mathMl());
            putIfPresent(ref, "latex", formula.latex());
            putIfPresent(ref, "plainText", formula.plainText());
            putIfPresent(ref, "assetId", formula.assetId());
            putIfPresent(ref, "model", formula.model());
            refs.add(ref);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(refs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize DOCX formula references", exception);
        }
    }


    static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }


    static String formulaEvidence(List<FormulaReference> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return "";
        }
        return formulas.stream()
                .map(FormulaReference::plainText)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }


    /**
     * Computes a SHA-256 checksum for parsed content.
     */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }


    /**
     * Removes the last file extension for fallback chapter names.
     */
    static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }


    /**
     * Verifies teacher/admin role.
     */
    static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher source sync execution requires teacher or admin role");
        }
    }


    /**
     * Creates an updated job response while preserving immutable fields.
     */
    static TeacherSourceSyncJobResponse updateJob(
            TeacherSourceSyncJobResponse job,
            String status,
            String phase,
            String stagingPath,
            String message) {
        return updateJob(job, status, phase, stagingPath, message, job.failure());
    }


    /** Updates a job while replacing provider failure details only for the terminal failure being recorded. */
    static TeacherSourceSyncJobResponse updateJob(
            TeacherSourceSyncJobResponse job,
            String status,
            String phase,
            String stagingPath,
            String message,
            TeacherSourceSyncFailureResponse failure) {
        return new TeacherSourceSyncJobResponse(
                job.jobId(),

                job.documentId(),
                job.tenantId(),
                job.sourceType(),
                job.operation(),
                status,
                phase,
                job.attempt(),
                job.createdBy(),
                stagingPath == null ? job.stagingPath() : stagingPath,
                message,

                job.createdAt(),
                Instant.now().toString(),
                failure);
    }


    /**
     * Returns stripped text or fallback.
     */
    static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }


    static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first.strip();
    }


    static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }


    static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (!needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
