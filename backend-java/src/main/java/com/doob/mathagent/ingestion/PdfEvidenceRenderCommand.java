package com.doob.mathagent.ingestion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** One-shot container command used only to create a hashable real-page visual evidence asset. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public final class PdfEvidenceRenderCommand implements ApplicationRunner {
    private static final String COMMAND = "gaokao:render-page-evidence";

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        List<String> raw = List.of(arguments.getSourceArgs());
        if (raw.isEmpty() || !COMMAND.equals(raw.getFirst())) {
            return;
        }
        String input = option(raw, "input");
        String output = option(raw, "output");
        int page = Integer.parseInt(option(raw, "page"));
        new PdfEvidencePageRenderer().render(Path.of(input), page, Path.of(output));
    }

    /** Requires each option exactly once so an evidence record cannot have ambiguous provenance. */
    private static String option(List<String> raw, String name) {
        String flag = "--" + name;
        for (int index = 1; index < raw.size() - 1; index++) {
            if (flag.equals(raw.get(index))) {
                return raw.get(index + 1);
            }
        }
        throw new IllegalArgumentException("Missing " + flag + " for " + COMMAND);
    }
}
