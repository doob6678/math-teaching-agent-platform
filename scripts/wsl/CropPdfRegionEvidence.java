/*
 * Docker evidence utility: converts PDF user-space region coordinates to pixels of an already rendered page PNG.
 * It is intentionally lossless apart from the requested crop; the original page PNG and source PDF remain intact.
 */
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CropPdfRegionEvidence {
    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException("usage: CropPdfRegionEvidence <page.png> <pdfWidth> <pdfHeight> <x1> <y1> <x2> <y2> <output.png>");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IllegalArgumentException("not a readable image: " + args[0]);
        double scaleX = source.getWidth() / Double.parseDouble(args[1]);
        double scaleY = source.getHeight() / Double.parseDouble(args[2]);
        int x1 = clamp((int) Math.floor(Double.parseDouble(args[3]) * scaleX), 0, source.getWidth() - 1);
        int y1 = clamp((int) Math.floor(Double.parseDouble(args[4]) * scaleY), 0, source.getHeight() - 1);
        int x2 = clamp((int) Math.ceil(Double.parseDouble(args[5]) * scaleX), x1 + 1, source.getWidth());
        int y2 = clamp((int) Math.ceil(Double.parseDouble(args[6]) * scaleY), y1 + 1, source.getHeight());
        Files.createDirectories(Path.of(args[7]).toAbsolutePath().getParent());
        if (!ImageIO.write(source.getSubimage(x1, y1, x2 - x1, y2 - y1), "png", Path.of(args[7]).toFile())) throw new IllegalStateException("PNG writer unavailable");
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
