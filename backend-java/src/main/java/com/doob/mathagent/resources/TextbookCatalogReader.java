package com.doob.mathagent.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextbookCatalogReader {

    private final ObjectMapper objectMapper;

    public TextbookCatalogReader() {
        this(new ObjectMapper());
    }

    TextbookCatalogReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TextbookCatalogItem> read(Path catalogJsonl) {
        try {
            List<TextbookCatalogItem> items = new ArrayList<>();
            for (String line : Files.readAllLines(catalogJsonl, StandardCharsets.UTF_8)) {
                String normalizedLine = stripUtf8Bom(line);
                if (!normalizedLine.isBlank()) {
                    items.add(objectMapper.readValue(normalizedLine, TextbookCatalogItem.class));
                }
            }
            return List.copyOf(items);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read textbook catalog: " + catalogJsonl, e);
        }
    }

    /**
     * Tolerates UTF-8 BOM emitted by Windows tools at the beginning of JSONL files.
     */
    private static String stripUtf8Bom(String line) {
        return line != null && line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
