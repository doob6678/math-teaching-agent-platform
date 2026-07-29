package com.doob.mathagent.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Converts DOCX into a PDF inside the Linux deployment image. This deliberately avoids the existing PowerShell/Word
 * helper so true-paper ingestion is reproducible in WSL Docker and enters the same PDF page-render pipeline.
 */
public final class DocxToPdfRenderer {
    private static final String LIBRE_OFFICE_EXECUTABLE = "soffice";
    private static final String PDF_EXPORT_FILTER = "pdf:writer_pdf_Export";
    private static final long CONVERSION_TIMEOUT_SECONDS = 90L;

    /** Builds the deterministic headless command used by the deployment image. */
    static List<String> command(Path sourceDocx, Path outputDirectory) {
        return List.of(
                LIBRE_OFFICE_EXECUTABLE,
                "--headless",
                "--convert-to",
                PDF_EXPORT_FILTER,
                "--outdir",
                outputDirectory.toAbsolutePath().normalize().toString(),
                sourceDocx.toAbsolutePath().normalize().toString());
    }

    /**
     * Performs one bounded local conversion and returns the exact derived PDF. Source files are never modified.
     *
     * @throws IOException when LibreOffice is unavailable, times out, fails, or emits no PDF
     */
    public Path render(Path sourceDocx, Path outputDirectory) throws IOException {
        if (sourceDocx == null || !Files.isRegularFile(sourceDocx)
                || !sourceDocx.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
            throw new IllegalArgumentException("sourceDocx must be an existing .docx file");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory is required");
        }
        Files.createDirectories(outputDirectory);
        Process process = new ProcessBuilder(command(sourceDocx, outputDirectory))
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("DOCX conversion timed out after " + CONVERSION_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("DOCX conversion interrupted", exception);
        }
        Path derivedPdf = outputDirectory.resolve(stripExtension(sourceDocx.getFileName().toString()) + ".pdf");
        if (process.exitValue() != 0 || !Files.isRegularFile(derivedPdf)) {
            throw new IOException("DOCX conversion failed with exit code " + process.exitValue());
        }
        return derivedPdf;
    }

    /** Removes only the final extension while preserving dots in the original filename. */
    private static String stripExtension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator <= 0 ? fileName : fileName.substring(0, separator);
    }
}
