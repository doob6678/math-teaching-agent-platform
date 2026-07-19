package com.doob.mathagent.teaching.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

/**
 * Renders real first-page preview images for PDF-backed teaching handout templates.
 */
@Service
public class TeachingHandoutTemplatePreviewService {

    private static final float PREVIEW_DPI = 132f;
    private static final int MAX_WIDTH = 980;

    private final TeachingHandoutTemplateService templateService;

    public TeachingHandoutTemplatePreviewService(TeachingHandoutTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * Returns a PNG preview for the first page of the selected local template when a real PDF exists.
     */
    public Optional<byte[]> renderPreviewPng(String templateCode) {
        Optional<Path> pdfPath = resolvePdfPath(templateCode);
        if (pdfPath.isEmpty()) {
            return Optional.empty();
        }
        try (PDDocument document = Loader.loadPDF(pdfPath.get().toFile());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (document.getNumberOfPages() <= 0) {
                return Optional.empty();
            }
            BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, PREVIEW_DPI, ImageType.RGB);
            BufferedImage normalized = rendered.getWidth() > MAX_WIDTH ? scaleToWidth(rendered, MAX_WIDTH) : rendered;
            ImageIO.write(normalized, "png", output);
            return Optional.of(output.toByteArray());
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the original local PDF bytes for full multipage preview in the frontend.
     */
    public Optional<byte[]> loadReferencePdf(String templateCode) {
        Optional<Path> pdfPath = resolvePdfPath(templateCode);
        if (pdfPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(pdfPath.get()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolvePdfPath(String templateCode) {
        TeachingHandoutTemplateProfile profile = templateService.resolve(templateCode);
        String referencePath = profile.summary().referencePath();
        if (referencePath == null || referencePath.isBlank()) {
            return Optional.empty();
        }
        Path pdfPath;
        try {
            pdfPath = Path.of(referencePath).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(pdfPath) || !pdfPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return Optional.empty();
        }
        return Optional.of(pdfPath);
    }

    private static BufferedImage scaleToWidth(BufferedImage source, int maxWidth) {
        int width = Math.max(1, maxWidth);
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }
}
