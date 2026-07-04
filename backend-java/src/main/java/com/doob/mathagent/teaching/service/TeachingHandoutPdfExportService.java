package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Renders a teaching handout into a lightweight PDF preview for protected downloads.
 */
@Service
public class TeachingHandoutPdfExportService {

    /**
     * Renders the task handout LaTeX source into a valid single-page PDF byte array.
     *
     * @param task owned teaching task
     * @return PDF bytes beginning with the PDF header
     */
    public byte[] render(TeachingTaskResponse task) {
        return render(task, "teacher");
    }

    /**
     * Renders one specific handout version into a valid single-page PDF byte array.
     *
     * @param task owned teaching task
     * @param version handout version code, such as teacher or student
     * @return PDF bytes beginning with the PDF header
     */
    public byte[] render(TeachingTaskResponse task, String version) {
        List<String> lines = handoutLines(task, version);
        String contentStream = contentStream(lines);
        return pdfDocument(contentStream);
    }

    /**
     * Builds short PDF text lines from the task metadata and LaTeX source.
     */
    private static List<String> handoutLines(TeachingTaskResponse task, String version) {
        List<String> lines = new ArrayList<>();
        lines.add("Math Agent Teaching Handout");
        lines.add("Version: " + safeAscii(version));
        lines.add("Task: " + task.taskId());
        lines.add("Learning goal: " + safeAscii(task.learningGoal()));
        lines.add("Question: " + safeAscii(task.questionText()));
        lines.add("LaTeX source preview:");
        for (String sourceLine : task.handoutLatexFor(version).split("\\R")) {
            if (!sourceLine.isBlank()) {
                lines.addAll(wrap(safeAscii(sourceLine), 86));
            }
            if (lines.size() >= 42) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Wraps one logical line to fit the simple PDF page width.
     */
    private static List<String> wrap(String line, int maxLength) {
        List<String> wrapped = new ArrayList<>();
        String remaining = line == null ? "" : line.strip();
        while (remaining.length() > maxLength) {
            wrapped.add(remaining.substring(0, maxLength));
            remaining = remaining.substring(maxLength);
        }
        if (!remaining.isBlank()) {
            wrapped.add(remaining);
        }
        return wrapped;
    }

    /**
     * Creates the PDF drawing commands for a single text page.
     */
    private static String contentStream(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        builder.append("BT\n/F1 10 Tf\n50 790 Td\n14 TL\n");
        for (String line : lines) {
            builder.append('(').append(escapePdfText(line)).append(") Tj\nT*\n");
        }
        builder.append("ET\n");
        return builder.toString();
    }

    /**
     * Builds a minimal PDF 1.4 document with catalog, page, font, and content stream objects.
     */
    private static byte[] pdfDocument(String contentStream) {
        byte[] streamBytes = contentStream.getBytes(StandardCharsets.ISO_8859_1);
        List<String> objects = List.of(
                "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
                "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
                "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
                "5 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n"
                        + contentStream + "endstream\nendobj\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(out.size());
            writeAscii(out, object);
        }
        int xrefOffset = out.size();
        writeAscii(out, "xref\n0 " + (objects.size() + 1) + "\n");
        writeAscii(out, "0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(out, "%010d 00000 n \n".formatted(offset));
        }
        writeAscii(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\n");
        writeAscii(out, "startxref\n" + xrefOffset + "\n%%EOF\n");
        return out.toByteArray();
    }

    /**
     * Escapes text for a PDF literal string.
     */
    private static String escapePdfText(String value) {
        return safeAscii(value)
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    /**
     * Keeps the lightweight PDF renderer inside the WinAnsi character set.
     */
    private static String safeAscii(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            builder.append(current >= 32 && current <= 126 ? current : '?');
        }
        return builder.toString();
    }

    /**
     * Writes ASCII PDF structure into the byte stream.
     */
    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }
}
