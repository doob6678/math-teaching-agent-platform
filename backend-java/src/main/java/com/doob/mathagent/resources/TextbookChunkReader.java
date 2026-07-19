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
public class TextbookChunkReader {

    private final ObjectMapper objectMapper;

    public TextbookChunkReader() {
        this(new ObjectMapper());
    }

    TextbookChunkReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TextbookChunk> read(Path chunksJsonl) {
        try {
            List<TextbookChunk> chunks = new ArrayList<>();
            for (String line : Files.readAllLines(chunksJsonl, StandardCharsets.UTF_8)) {
                String normalizedLine = stripUtf8Bom(line);
                if (!normalizedLine.isBlank()) {
                    chunks.add(objectMapper.readValue(normalizedLine, TextbookChunk.class));
                }
            }
            return List.copyOf(chunks);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read textbook chunks: " + chunksJsonl, e);
        }
    }

    /**
     * Tolerates UTF-8 BOM emitted by Windows tools at the beginning of JSONL files.
     */
    private static String stripUtf8Bom(String line) {
        return line != null && line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
