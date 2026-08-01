package com.doob.mathagent.student.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Prepares the owner-validated student image for a multimodal model call.
 * The uploaded file stays untouched; only a bounded PNG copy is sent to the provider.
 */
public final class StudentExplanationModelImageService {

    private static final int MAX_LONG_EDGE = 1536;

    private StudentExplanationModelImageService() {
    }

    /** Builds a provider payload and auditable dimensions/size metrics. */
    public static PreparedImage prepare(StudentExplanationImageRecord record) {
        if (record == null) return PreparedImage.empty();
        try {
            Path source = record.localPath().toAbsolutePath().normalize();
            BufferedImage original = ImageIO.read(source.toFile());
            if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
                throw new IllegalStateException("Uploaded image cannot be decoded");
            }
            BufferedImage resized = resize(original);
            byte[] bytes = encodePng(resized);
            return new PreparedImage(
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                    original.getWidth(), original.getHeight(), resized.getWidth(), resized.getHeight(),
                    Files.size(source), bytes.length, 0, "");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Uploaded image could not be prepared for model context", exception);
        }
    }

    private static BufferedImage resize(BufferedImage original) {
        int longEdge = Math.max(original.getWidth(), original.getHeight());
        if (longEdge <= MAX_LONG_EDGE) return original;
        double scale = (double) MAX_LONG_EDGE / longEdge;
        int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(original, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder is unavailable");
            return output.toByteArray();
        }
    }

    /** Response-safe image metrics; raw bytes and local paths are intentionally omitted. */
    public record PreparedImage(
            String dataUrl,
            int originalWidth,
            int originalHeight,
            int sentWidth,
            int sentHeight,
            long originalBytes,
            long sentBytes,
            int estimatedImageTokens,
            String failureCode) {
        private static PreparedImage empty() {
            return new PreparedImage("", 0, 0, 0, 0, 0L, 0L, 0, "");
        }

        /** Whether a valid provider payload was generated. */
        public boolean available() {
            return !dataUrl.isBlank();
        }
    }
}
