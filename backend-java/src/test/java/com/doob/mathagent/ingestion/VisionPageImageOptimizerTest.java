package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the production derivative preserves aspect ratio while enforcing the configured token-saving edge bound. */
class VisionPageImageOptimizerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsBoundedJpegDerivativeWithoutOverwritingOriginal() throws Exception {
        Path original = temporaryDirectory.resolve("page.png");
        BufferedImage image = new BufferedImage(1322, 1870, BufferedImage.TYPE_INT_RGB);
        image.setRGB(20, 20, Color.BLACK.getRGB());
        ImageIO.write(image, "png", original.toFile());
        Path derivative = temporaryDirectory.resolve("page-initial-review.jpg");

        new VisionPageImageOptimizer().optimize(original, derivative, 960, 0.82F);

        BufferedImage optimized = ImageIO.read(derivative.toFile());
        assertThat(Files.isRegularFile(original)).isTrue();
        assertThat(optimized.getWidth()).isEqualTo(679);
        assertThat(optimized.getHeight()).isEqualTo(960);
    }
}
