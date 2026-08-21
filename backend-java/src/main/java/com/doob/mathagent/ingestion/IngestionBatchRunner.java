package com.doob.mathagent.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Executes the explicitly requested batch command after Flyway.  It never runs on an ordinary web-server startup,
 * which prevents a mounted collection from being silently imported merely because the container restarted.
 *
 * <p>The first durable pass extracts actual PDF text and creates page-backed question candidates. Text/formula
 * candidates with non-empty text are deterministically stored as {@code AUTO_APPROVED_TEXT_FORMULA} canonical rows so
 * they can be retrieved immediately. Their rectangles remain pending visual review: text order alone cannot safely
 * claim a column-level crop, diagram ownership, a cross-page continuation, an answer, or public publication.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class IngestionBatchRunner implements ApplicationRunner {
    private static final String PENDING_VISUAL_REVIEW = "PENDING_VISUAL_REVIEW";
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final IngestionProperties properties;
    private final IngestionPreflightService preflightService = new IngestionPreflightService(new IngestionSourceFileDiscoverer());
    private final PdfEvidencePageRenderer pageRenderer = new PdfEvidencePageRenderer();
    private final VisionPageImageOptimizer pageImageOptimizer = new VisionPageImageOptimizer();

    public IngestionBatchRunner(DataSource dataSource, ObjectMapper objectMapper, IngestionProperties properties) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Parses raw arguments so the documented command works equally in Docker and a one-shot Spring invocation. */
    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {
        List<String> sourceArguments = List.of(applicationArguments.getSourceArgs());
        if (sourceArguments.isEmpty() || !IngestionCommandArguments.COMMAND.equals(sourceArguments.getFirst())) {
            return;
        }
        execute(IngestionCommandArguments.parse(sourceArguments));
    }

    /** Performs the durable parse pass, auto-approving only non-empty text/formula storage records. */
    void execute(IngestionCommandArguments arguments) throws IOException, SQLException {
        IngestionPreflight preflight = restrictToConfiguredSources(preflightService.prepare(arguments));
        String runId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertRun(connection, runId, arguments, preflight);
            try {
                for (DiscoveredSourceFile source : preflight.files()) {
                    parseSource(connection, runId, source);
                }
                // Parsing completed successfully. Review is pending, not failed: keep operational run status and
                // independent verification status semantically distinct so operators can schedule the next gate.
                updateRun(connection, runId, ImportRunState.PARSED_AWAITING_REVIEW.name(), ImportVerificationState.NOT_STARTED.name(),
                        "PDF text candidates persisted; visual regions, Golden comparison and human approval remain required.");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Applies the reviewed configuration whitelist and fails closed when a named source is absent from the mount. */
    private IngestionPreflight restrictToConfiguredSources(IngestionPreflight discovered) {
        List<String> configuredNames = properties.getSelectedSourceFileNames();
        if (configuredNames.isEmpty()) {
            return discovered;
        }
        List<DiscoveredSourceFile> selected = discovered.files().stream()
                .filter(source -> configuredNames.contains(source.fileName()))
                .toList();
        Set<String> foundNames = selected.stream().map(DiscoveredSourceFile::fileName).collect(Collectors.toSet());
        if (!foundNames.containsAll(configuredNames)) {
            Set<String> missing = new java.util.TreeSet<>(configuredNames);
            missing.removeAll(foundNames);
            throw new IllegalStateException("Configured ingestion source files are missing: " + missing);
        }
        return new IngestionPreflight(discovered.arguments(), selected,
                new ImportRunProgress(selected.size(), 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private void insertRun(Connection connection, String runId, IngestionCommandArguments arguments, IngestionPreflight preflight) throws SQLException {
        String sql = "INSERT INTO import_run (import_run_id,paper_type,status,verification_status,input_root,requested_model,progress_json,evidence_path) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, arguments.paperType().name());
            statement.setString(3, ImportRunState.PARSING_ALL_FILES.name());
            statement.setString(4, ImportVerificationState.NOT_STARTED.name());
            statement.setString(5, arguments.inputRoot());
            statement.setString(6, arguments.model() == null ? "gpt-5.6-luna" : arguments.model());
            statement.setString(7, json(Map.of("discoveredFiles", preflight.files().size(), "candidateQuestions", 0)));
            statement.setString(8, properties.getEvidenceRoot());
            statement.executeUpdate();
        }
    }

    private void parseSource(Connection connection, String runId, DiscoveredSourceFile source) throws IOException, SQLException {
        String sourceId = UUID.randomUUID().toString();
        insertSource(connection, sourceId, runId, source);
        if (!"application/pdf".equals(source.mediaType())) {
            updateSource(connection, sourceId, "REQUIRES_PDF_RENDER", null, "DOCX must be rendered with LibreOffice before the shared PDF parser.");
            return;
        }
        try (PDDocument document = Loader.loadPDF(source.path().toFile())) {
            updateSource(connection, sourceId, "PARSING", document.getNumberOfPages(), null);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                String pageText = extractPageText(document, pageIndex + 1);
                PDRectangle box = document.getPage(pageIndex).getMediaBox();
                PageEvidence evidence = renderInitialReviewEvidence(runId, source, pageIndex + 1);
                persistPageCandidates(connection, runId, sourceId, source.sha256(), source.fileName(), pageIndex + 1, box, pageText, evidence);
            }
            updateSource(connection, sourceId, "PARSED_PENDING_VISUAL_REVIEW", document.getNumberOfPages(), null);
        } catch (IOException exception) {
            updateSource(connection, sourceId, "FAILED", null, exception.getMessage());
            throw exception;
        }
    }

    private String extractPageText(PDDocument document, int pageNumber) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document);
    }

    private PageEvidence renderInitialReviewEvidence(String runId, DiscoveredSourceFile source, int pageNumber) throws IOException {
        Path directory = Path.of(properties.getEvidenceRoot()).resolve("runs").resolve(runId).resolve(source.sha256());
        Path original = directory.resolve("page-" + pageNumber + ".png");
        Path initialReview = directory.resolve("page-" + pageNumber + "-initial-review.jpg");
        pageRenderer.render(source.path(), pageNumber, original);
        pageImageOptimizer.optimize(original, initialReview, properties.getInitialReviewMaxLongEdgePixels(), properties.getInitialReviewJpegQuality());
        return new PageEvidence(original, initialReview);
    }

    private void persistPageCandidates(Connection connection, String runId, String sourceId, String sourceHash, String sourceFileName,
                                       int pageNumber, PDRectangle box, String pageText, PageEvidence evidence) throws SQLException {
        List<String> lines = pageText.lines().toList();
        // PDF producers can emit the same printed glyph through multiple text-layer fragments. Until visual layout
        // has trustworthy region boxes, one top-level number can produce only one source candidate on a page.
        Set<String> pageQuestionNumbers = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            var number = QuestionNumberDetector.topLevelNumber(lines.get(index));
            if (number.isEmpty() || !pageQuestionNumbers.add(number.get())) {
                continue;
            }
            StringBuilder candidateText = new StringBuilder(lines.get(index));
            for (int next = index + 1; next < lines.size() && QuestionNumberDetector.topLevelNumber(lines.get(next)).isEmpty(); next++) {
                candidateText.append('\n').append(lines.get(next));
            }
            QuestionRegion pageRegion = new QuestionRegion(Math.max(0, Math.round(box.getLowerLeftX())), Math.max(0, Math.round(box.getLowerLeftY())),
                    Math.round(box.getUpperRightX()), Math.round(box.getUpperRightY()));
            Map<String, Object> region = Map.of("x1", pageRegion.x1(), "y1", pageRegion.y1(),
                    "x2", pageRegion.x2(), "y2", pageRegion.y2(), "coordinateSpace", "PDF_MEDIA_BOX");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", candidateText.toString());
            content.put("extraction", "PDF_TEXT_LAYER");
            content.put("requiresVisualReview", true);
            content.put("reason", "Text extraction identifies a question-number candidate but cannot prove a column crop.");
            content.put("originalPageImage", evidence.original().toString());
            content.put("initialReviewImage", evidence.initialReview().toString());
            String occurrenceId = UUID.randomUUID().toString();
            String regionJson = json(region);
            String sql = "INSERT INTO question_source_occurrence (occurrence_id,source_file_id,page_start,page_end,region_json,region_fingerprint,original_question_number,recognized_content_json,occurrence_status) VALUES (?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, occurrenceId);
                statement.setString(2, sourceId);
                statement.setInt(3, pageNumber);
                statement.setInt(4, pageNumber);
                statement.setString(5, regionJson);
                statement.setString(6, QuestionOccurrenceIdentity.fingerprint(sourceHash, pageNumber, pageNumber, pageRegion, number.get()));
                statement.setString(7, number.get());
                statement.setString(8, json(content));
                statement.setString(9, PENDING_VISUAL_REVIEW);
                statement.executeUpdate();
            }
            // This is intentionally after the source occurrence insert: the canonical row references a durable,
            // source-scoped occurrence and can be safely re-created from it if later visual review rejects the crop.
            TextFormulaCandidateApproval approval = TextFormulaCandidateApproval.approve(sourceHash, pageNumber, number.get(), candidateText.toString());
            String canonicalQuestionId = UUID.randomUUID().toString();
            insertTextFormulaCanonicalQuestion(connection, canonicalQuestionId, approval, sourceFileName, pageNumber, number.get());
            linkOccurrenceToCanonicalQuestion(connection, occurrenceId, canonicalQuestionId);
            audit(connection, runId, occurrenceId, "TEXT_FORMULA_AUTO_APPROVED", Map.of(
                    "canonicalQuestionId", canonicalQuestionId,
                    "storageStatus", TextFormulaCandidateApproval.STORAGE_APPROVED_STATUS,
                    "answerApproved", false,
                    "visualAssetsApproved", false));
            audit(connection, runId, occurrenceId, "QUESTION_NUMBER_CANDIDATE", Map.of("page", pageNumber, "questionNumber", number.get(), "visualReviewRequired", true));
        }
    }

    /** Persists only the source text; formula text stays verbatim until a dedicated formula parser can prove structure. */
    private void insertTextFormulaCanonicalQuestion(Connection connection, String canonicalQuestionId,
                                                     TextFormulaCandidateApproval approval, String sourceFileName,
                                                     int pageNumber, String questionNumber) throws SQLException {
        String sql = "INSERT INTO canonical_question (canonical_question_id,paper_type,content_json,formula_canonical_json,publication_status,fingerprint,display_citation) VALUES (?,?,?,?,?,?,?)";
        Map<String, Object> content = Map.of("blocks", List.of(Map.of("type", "text", "value", approval.normalizedText())),
                "extraction", "PDF_TEXT_LAYER", "formulaHandling", "VERBATIM_PENDING_STRUCTURED_PARSE");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, canonicalQuestionId);
            statement.setString(2, PaperType.GAOKAO.name());
            statement.setString(3, json(content));
            statement.setString(4, "[]");
            statement.setString(5, TextFormulaCandidateApproval.STORAGE_APPROVED_STATUS);
            statement.setString(6, approval.fingerprint());
            statement.setString(7, sourceFileName + " 第" + pageNumber + "页 第" + questionNumber + "题");
            statement.executeUpdate();
        }
    }

    /** Links an already-created occurrence to its canonical text/formula record without changing visual-review state. */
    private void linkOccurrenceToCanonicalQuestion(Connection connection, String occurrenceId, String canonicalQuestionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE question_source_occurrence SET canonical_question_id=? WHERE occurrence_id=?")) {
            statement.setString(1, canonicalQuestionId);
            statement.setString(2, occurrenceId);
            statement.executeUpdate();
        }
    }

    private void insertSource(Connection connection, String sourceId, String runId, DiscoveredSourceFile source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO import_source_file (source_file_id,import_run_id,source_file_hash,source_file_name,media_type,parse_status,metadata_json) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, sourceId); statement.setString(2, runId); statement.setString(3, source.sha256());
            statement.setString(4, source.fileName()); statement.setString(5, source.mediaType()); statement.setString(6, "DISCOVERED");
            statement.setString(7, json(Map.of("absolutePath", source.path().toString()))); statement.executeUpdate();
        }
    }

    private void updateSource(Connection connection, String sourceId, String status, Integer pageCount, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE import_source_file SET parse_status=?, page_count=?, failure_summary=? WHERE source_file_id=?")) {
            statement.setString(1, status); if (pageCount == null) statement.setNull(2, java.sql.Types.INTEGER); else statement.setInt(2, pageCount);
            statement.setString(3, failure); statement.setString(4, sourceId); statement.executeUpdate();
        }
    }

    private void updateRun(Connection connection, String runId, String status, String verificationStatus, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE import_run SET status=?, verification_status=?, failure_summary=? WHERE import_run_id=?")) {
            statement.setString(1, status); statement.setString(2, verificationStatus); statement.setString(3, failure); statement.setString(4, runId); statement.executeUpdate();
        }
    }

    private void audit(Connection connection, String runId, String occurrenceId, String action, Map<String, Object> decision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO question_ingestion_audit (audit_id,import_run_id,occurrence_id,action_type,actor_type,decision_json) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, runId); statement.setString(3, occurrenceId);
            statement.setString(4, action); statement.setString(5, "SYSTEM_RULE"); statement.setString(6, json(decision)); statement.executeUpdate();
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Unable to serialize ingestion evidence", exception); }
    }

    /** Keeps both image variants associated with the same page; only the JPEG may be sent for low-cost page review. */
    private record PageEvidence(Path original, Path initialReview) { }
}
