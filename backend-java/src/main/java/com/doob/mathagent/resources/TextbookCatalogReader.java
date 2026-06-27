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
                if (!line.isBlank()) {
                    items.add(objectMapper.readValue(line, TextbookCatalogItem.class));
                }
            }
            return List.copyOf(items);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read textbook catalog: " + catalogJsonl, e);
        }
    }
}
