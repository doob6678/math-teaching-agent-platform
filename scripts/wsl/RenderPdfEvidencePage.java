/*
 * Standalone evidence utility: it runs inside the backend Docker image with the exact PDFBox dependency that the
 * service uses. Keeping it framework-free isolates PDF rendering from Redis, AMQP and web-server startup failures.
 */
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RenderPdfEvidencePage {
    /** DPI balances legible Chinese exam text against the payload limit of the visual audit request. */
    private static final float EVIDENCE_DPI = 160.0F;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("usage: RenderPdfEvidencePage <source.pdf> <oneBasedPage> <output.png>");
        }
        Path source = Path.of(arguments[0]);
        int page = Integer.parseInt(arguments[1]);
        Path output = Path.of(arguments[2]);
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var document = Loader.loadPDF(source.toFile())) {
            if (page > document.getNumberOfPages()) {
                throw new IllegalArgumentException("page exceeds document page count: " + document.getNumberOfPages());
            }
            var image = new PDFRenderer(document).renderImageWithDPI(page - 1, EVIDENCE_DPI, ImageType.RGB);
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
        }
    }
}
