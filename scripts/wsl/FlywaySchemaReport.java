/* Reads the authoritative Flyway history from Docker MySQL for the deployment report. */
import java.sql.DriverManager;

public final class FlywaySchemaReport {
    public static void main(String[] args) throws Exception {
        try (var db = DriverManager.getConnection(System.getenv("MATH_AGENT_DB_URL"), System.getenv("MATH_AGENT_DB_USERNAME"), System.getenv("MATH_AGENT_DB_PASSWORD"));
             var statement = db.prepareStatement("SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Flyway history is empty");
            System.out.println("{\"version\":\"" + rows.getString(1) + "\",\"description\":\"" + rows.getString(2) + "\",\"success\":" + rows.getBoolean(3) + "}");
        }
    }
}
