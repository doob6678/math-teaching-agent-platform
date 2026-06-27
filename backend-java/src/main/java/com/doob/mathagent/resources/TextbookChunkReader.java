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
                if (!line.isBlank()) {
                    chunks.add(objectMapper.readValue(line, TextbookChunk.class));
                }
            }
            return List.copyOf(chunks);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read textbook chunks: " + chunksJsonl, e);
        }
    }
}
