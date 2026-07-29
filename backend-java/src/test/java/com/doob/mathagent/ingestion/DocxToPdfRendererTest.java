package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks the DOCX conversion command to the Linux/Docker renderer rather than Windows PowerShell. */
class DocxToPdfRendererTest {

    @Test
    void buildsHeadlessLibreOfficeCommandForDocker() {
        var command = DocxToPdfRenderer.command(Path.of("/data/paper.docx"), Path.of("/work/output"));

        assertThat(command.subList(0, 6))
                .containsExactly("soffice", "--headless", "--convert-to", "pdf:writer_pdf_Export", "--outdir", command.get(5));
        assertThat(command.get(5)).endsWith("work" + java.io.File.separator + "output");
        assertThat(command.get(6)).endsWith("data" + java.io.File.separator + "paper.docx");
    }
}
