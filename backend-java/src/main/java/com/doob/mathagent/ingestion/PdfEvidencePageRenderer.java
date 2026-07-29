package com.doob.mathagent.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Renders a source PDF page with PDFBox so the image inspected by Luna has a reproducible local provenance. */
public final class PdfEvidencePageRenderer {
    /** Chosen for legible exam text while keeping one evidence image below the remote-request size limit. */
    private static final float EVIDENCE_DPI = 160.0F;

    /** Writes one PNG atomically enough for evidence collection; the input PDF is always opened read-only. */
    public void render(Path sourcePdf, int oneBasedPage, Path outputPng) throws IOException {
        if (oneBasedPage < 1) {
            throw new IllegalArgumentException("page must be one-based and positive");
        }
        Files.createDirectories(outputPng.toAbsolutePath().getParent());
        try (var document = Loader.loadPDF(sourcePdf.toFile())) {
            if (oneBasedPage > document.getNumberOfPages()) {
                throw new IllegalArgumentException("page exceeds PDF page count: " + document.getNumberOfPages());
            }
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(oneBasedPage - 1, EVIDENCE_DPI, ImageType.RGB);
            if (!ImageIO.write(page, "png", outputPng.toFile())) {
                throw new IOException("No PNG writer is available for evidence rendering");
            }
        }
    }
}
