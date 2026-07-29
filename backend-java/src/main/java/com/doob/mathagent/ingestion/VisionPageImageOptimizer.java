package com.doob.mathagent.ingestion;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Creates the verified low-token JPEG derivative while retaining the source PNG untouched for high-detail review. */
public final class VisionPageImageOptimizer {
    public void optimize(Path sourcePng, Path outputJpeg, int maximumLongEdgePixels, float jpegQuality) throws IOException {
        if (maximumLongEdgePixels < 1 || jpegQuality <= 0 || jpegQuality > 1) throw new IllegalArgumentException("invalid vision derivative configuration");
        BufferedImage source = ImageIO.read(sourcePng.toFile());
        if (source == null) throw new IOException("unable to read source page image: " + sourcePng);
        double scale = Math.min(1, maximumLongEdgePixels / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        Files.createDirectories(outputJpeg.toAbsolutePath().getParent());
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer is unavailable");
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(outputJpeg.toFile())) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(jpegQuality);
            writer.write(null, new IIOImage(target, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }
}
