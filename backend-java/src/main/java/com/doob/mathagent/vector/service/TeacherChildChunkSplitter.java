package com.doob.mathagent.vector.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic paragraph-level child-chunk splitter for teacher text blocks (parent-child / small-to-big retrieval).
 *
 * <p>Production blocks are heading-bounded and can contain several paragraphs, so one block embedding averages many
 * topics and the block that actually answers the query often loses its own vector anchor to a neighbor. Indexing
 * smaller child chunks and snapping each child hit back to its parent block restores exact parent-level hits without
 * any extra rerank pass. Children are NOT persisted in MySQL: they are re-derived from the block text on every index
 * rebuild, so this splitter must stay a pure, stable function of the block text.</p>
 */
public final class TeacherChildChunkSplitter {

    /**
     * Fits comfortably inside the bge-small-zh 512-token context. 20260830 实测：480 时约 19% 的块被切分；
     * 240 虽多切但 doc@1 跌破 gate 且 exact 不动，故定稿 480。
     */
    public static final int MAX_CHUNK_CHARS = 480;

    /** Trailing pieces smaller than this fold into the previous chunk instead of becoming a weak standalone vector. */
    public static final int MIN_MERGE_CHARS = 40;

    private TeacherChildChunkSplitter() {
    }

    /**
     * Splits one block's normalized text into ordered child chunk texts.
     *
     * <p>Rules: blank-line paragraphs are packed greedily up to {@value MAX_CHUNK_CHARS} chars; a single paragraph
     * longer than the cap is cut at sentence boundaries (。！？；!?) and only then hard-wrapped; a trailing piece below
     * {@value MIN_MERGE_CHARS} merges into its predecessor. Blank input yields no chunks, mirroring the blank-block
     * admission rule of the block index.</p>
     */
    public static List<String> split(String normalizedText) {
        String text = normalizedText == null ? "" : normalizedText.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= MAX_CHUNK_CHARS) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n+")) {
            String cleaned = paragraph.strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (cleaned.length() <= MAX_CHUNK_CHARS) {
                pieces.add(cleaned);
                continue;
            }
            pieces.addAll(splitLongParagraph(cleaned));
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.length() > 0
                    && current.length() + piece.length() + 1 > MAX_CHUNK_CHARS
                    && current.length() >= MIN_MERGE_CHARS) {
                chunks.add(current.toString().strip());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            String tail = current.toString().strip();
            if (tail.length() < MIN_MERGE_CHARS && !chunks.isEmpty()) {
                chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n" + tail);
            } else if (!tail.isEmpty()) {
                chunks.add(tail);
            }
        }
        return List.copyOf(chunks);
    }

    /** Cuts one over-long paragraph at sentence boundaries, then hard-wraps any sentence still above the cap. */
    private static List<String> splitLongParagraph(String paragraph) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sentence = new StringBuilder();
        for (int index = 0; index < paragraph.length(); index += 1) {
            char value = paragraph.charAt(index);
            sentence.append(value);
            boolean sentenceEnd = "。！？；!?;".indexOf(value) >= 0;
            if (sentenceEnd && sentence.length() >= MIN_MERGE_CHARS) {
                sentences.add(sentence.toString().strip());
                sentence = new StringBuilder();
            } else if (sentence.length() >= MAX_CHUNK_CHARS) {
                sentences.add(sentence.toString().strip());
                sentence = new StringBuilder();
            }
        }
        if (sentence.toString().strip().length() > 0) {
            sentences.add(sentence.toString().strip());
        }
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : sentences) {
            if (current.length() + part.length() + 1 > MAX_CHUNK_CHARS && current.length() > 0) {
                packed.add(current.toString().strip());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(part);
        }
        if (current.toString().strip().length() > 0) {
            packed.add(current.toString().strip());
        }
        return packed;
    }
}
