/* Docker evidence CLI: emits real PDFBox-derived question-region coordinates as JSON Lines. */
import com.doob.mathagent.ingestion.DetectedQuestionRegion;
import com.doob.mathagent.ingestion.PdfQuestionRegionDetector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DetectPdfQuestionRegions {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("usage: DetectPdfQuestionRegions <source.pdf> [output.jsonl]");
        List<String> lines = new ArrayList<>();
        for (DetectedQuestionRegion region : new PdfQuestionRegionDetector().detect(Path.of(args[0]))) {
            lines.add("{\"page\":" + region.pageNumber() + ",\"questionNumber\":\"" + region.questionNumber() + "\",\"region\":{\"x1\":" + region.region().x1() + ",\"y1\":" + region.region().y1() + ",\"x2\":" + region.region().x2() + ",\"y2\":" + region.region().y2() + "},\"layout\":\"" + region.layout() + "\"}");
        }
        String output = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        if (args.length == 2) Files.writeString(Path.of(args[1]), output);
        System.out.print(output);
    }
}
