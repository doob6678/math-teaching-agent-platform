/*
 * Produces a bounded-size JPEG derivative for a vision request. The original PNG is never modified; the maximum
 * edge and JPEG quality are explicit command inputs so a token/cost experiment is reproducible rather than guessed.
 */
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class OptimizeVisionPage {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("usage: OptimizeVisionPage <source.png> <maxLongEdge> <jpegQuality0to1> <output.jpg>");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IllegalArgumentException("unreadable source image");
        int maxLongEdge = Integer.parseInt(args[1]);
        float quality = Float.parseFloat(args[2]);
        if (maxLongEdge < 1 || quality <= 0 || quality > 1) throw new IllegalArgumentException("invalid edge or quality");
        double scale = Math.min(1, maxLongEdge / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        Files.createDirectories(Path.of(args[3]).toAbsolutePath().getParent());
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(Path.of(args[3]).toFile())) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(target, null, null), parameters);
        } finally {
            writer.dispose();
        }
        System.out.println("{\"sourceWidth\":" + source.getWidth() + ",\"sourceHeight\":" + source.getHeight() + ",\"width\":" + width + ",\"height\":" + height + ",\"quality\":" + quality + "}");
    }
}
