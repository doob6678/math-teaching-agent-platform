/* Queries the live Docker MySQL tables for one import run without exposing database credentials in output. */
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;

public final class IngestionDatabaseReport {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("usage: IngestionDatabaseReport <run-id> [output.json]");
        try (var db = DriverManager.getConnection(System.getenv("MATH_AGENT_DB_URL"), System.getenv("MATH_AGENT_DB_USERNAME"), System.getenv("MATH_AGENT_DB_PASSWORD"))) {
            String sql = "SELECT r.status,r.verification_status,r.progress_json,COUNT(DISTINCT s.source_file_id) files,COUNT(o.occurrence_id) occurrences,COUNT(o.canonical_question_id) published_links FROM import_run r LEFT JOIN import_source_file s ON s.import_run_id=r.import_run_id LEFT JOIN question_source_occurrence o ON o.source_file_id=s.source_file_id WHERE r.import_run_id=? GROUP BY r.import_run_id,r.status,r.verification_status,r.progress_json";
            try (var statement = db.prepareStatement(sql)) { statement.setString(1, args[0]); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalStateException("run not found"); String output = "{\"importRunId\":\"" + args[0] + "\",\"status\":\"" + rows.getString(1) + "\",\"verificationStatus\":\"" + rows.getString(2) + "\",\"progress\":" + rows.getString(3) + ",\"files\":" + rows.getInt(4) + ",\"occurrences\":" + rows.getInt(5) + ",\"publishedLinks\":" + rows.getInt(6) + "}"; if (args.length == 2) Files.writeString(Path.of(args[1]), output); System.out.println(output); } }
        }
    }
}
