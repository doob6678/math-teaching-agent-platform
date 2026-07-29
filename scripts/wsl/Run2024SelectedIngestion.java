/*
 * Docker-only recovery runner for the 2024 selected-paper batch.  It uses the backend image's PDFBox and MySQL
 * libraries but intentionally avoids the full Spring/Redisson bootstrap: a one-shot import must remain possible
 * when unrelated web-worker infrastructure is unavailable.  It writes the same durable import/audit tables.
 */
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Run2024SelectedIngestion {
    private static final Pattern QUESTION = Pattern.compile("^\\s*([1-9][0-9]*)\\s*(?:[.．、]|[.．]\\s*\\([0-9]+\\))");
    private static final String MODEL = "gpt-5.6-luna";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("usage: Run2024SelectedIngestion <six-file-manifest-root>");
        Path root = Path.of(arguments[0]);
        var files = Files.list(root).filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".pdf")).sorted().toList();
        if (files.size() != 6) throw new IllegalStateException("exactly six configured PDFs are required; discovered=" + files.size());
        String run = UUID.randomUUID().toString();
        try (Connection db = DriverManager.getConnection(required("MATH_AGENT_DB_URL"), required("MATH_AGENT_DB_USERNAME"), required("MATH_AGENT_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            insertRun(db, run, root, files.size());
            int candidates = 0;
            for (Path file : files) candidates += parseFile(db, run, file);
            try (PreparedStatement statement = db.prepareStatement("UPDATE import_run SET status=?,verification_status=?,progress_json=?,failure_summary=? WHERE import_run_id=?")) {
                statement.setString(1, "PARTIALLY_FAILED"); statement.setString(2, "VERIFICATION_FAILED");
                statement.setString(3, "{\"discoveredFiles\":6,\"candidateQuestions\":" + candidates + ",\"excludedFiles\":8}");
                statement.setString(4, "Real PDF text candidates persisted. Visual crop, Golden comparison, answer review and publication are intentionally pending."); statement.setString(5, run); statement.executeUpdate();
            }
            db.commit();
            System.out.println("{\"importRunId\":\"" + run + "\",\"selectedFiles\":6,\"questionCandidates\":" + candidates + ",\"status\":\"PARTIALLY_FAILED\"}");
        }
    }

    private static int parseFile(Connection db, String run, Path file) throws Exception {
        String sourceId = UUID.randomUUID().toString(), hash = sha256(file);
        try (PreparedStatement statement = db.prepareStatement("INSERT INTO import_source_file (source_file_id,import_run_id,source_file_hash,source_file_name,media_type,parse_status,metadata_json) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, sourceId); statement.setString(2, run); statement.setString(3, hash); statement.setString(4, file.getFileName().toString());
            statement.setString(5, "application/pdf"); statement.setString(6, "PARSING"); statement.setString(7, "{\"selectedBy\":\"config/gaokao-ingestion-2024.json\"}"); statement.executeUpdate();
        }
        int candidates = 0;
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper text = new PDFTextStripper(); text.setStartPage(page); text.setEndPage(page);
                PDRectangle box = document.getPage(page - 1).getMediaBox();
                // The temporary full-page visual region cannot distinguish duplicate text-layer echoes of the same
                // printed number. Keep one candidate per page/number until visual cropping supplies real regions.
                Set<String> pageNumbers = new HashSet<>();
                for (String line : text.getText(document).lines().toList()) {
                    Matcher matcher = QUESTION.matcher(line); if (!matcher.find()) continue;
                    String number = matcher.group(1); String region = "{\"x1\":0,\"y1\":0,\"x2\":" + Math.round(box.getWidth()) + ",\"y2\":" + Math.round(box.getHeight()) + ",\"coordinateSpace\":\"PDF_MEDIA_BOX\"}";
                    if (!pageNumbers.add(number)) continue;
                    String fingerprint = sha256(hash + "|" + page + "|" + number);
                    try (PreparedStatement statement = db.prepareStatement("INSERT INTO question_source_occurrence (occurrence_id,source_file_id,page_start,page_end,region_json,region_fingerprint,original_question_number,recognized_content_json,occurrence_status) VALUES (?,?,?,?,?,?,?,?,?)")) {
                        statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, sourceId); statement.setInt(3, page); statement.setInt(4, page);
                        statement.setString(5, region); statement.setString(6, fingerprint); statement.setString(7, number);
                        statement.setString(8, "{\"text\":\"" + escape(line) + "\",\"extraction\":\"PDF_TEXT_LAYER\",\"requiresVisualReview\":true}");
                        statement.setString(9, "PENDING_VISUAL_REVIEW"); statement.executeUpdate();
                    }
                    candidates++;
                }
            }
            try (PreparedStatement statement = db.prepareStatement("UPDATE import_source_file SET parse_status=?,page_count=? WHERE source_file_id=?")) {
                statement.setString(1, "PARSED_PENDING_VISUAL_REVIEW"); statement.setInt(2, document.getNumberOfPages()); statement.setString(3, sourceId); statement.executeUpdate();
            }
        }
        return candidates;
    }

    private static void insertRun(Connection db, String run, Path root, int files) throws Exception {
        try (PreparedStatement statement = db.prepareStatement("INSERT INTO import_run (import_run_id,paper_type,status,verification_status,input_root,requested_model,progress_json,evidence_path) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, run); statement.setString(2, "GAOKAO"); statement.setString(3, "PARSING_ALL_FILES"); statement.setString(4, "NOT_STARTED");
            statement.setString(5, root.toString()); statement.setString(6, MODEL); statement.setString(7, "{\"discoveredFiles\":" + files + ",\"candidateQuestions\":0}"); statement.setString(8, "/app/data/gaokao-evidence/2024"); statement.executeUpdate();
        }
    }

    private static String required(String key) { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required by Docker service configuration"); return value; }
    private static String sha256(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream stream = Files.newInputStream(file)) { for (byte[] part = new byte[8192];;) { int read = stream.read(part); if (read < 0) break; digest.update(part, 0, read); } } return HexFormat.of().formatHex(digest.digest()); }
    private static String sha256(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
}
