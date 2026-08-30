package com.doob.mathagent.vector.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pin the deterministic child-chunk split rules that the Milvus child collection depends on. */
class TeacherChildChunkSplitterTest {

    @Test
    void shortTextStaysOneChunk() {
        assertEquals(List.of("椭圆离心率 e=c/a"), TeacherChildChunkSplitter.split("椭圆离心率 e=c/a"));
    }

    @Test
    void blankTextYieldsNoChunks() {
        assertTrue(TeacherChildChunkSplitter.split("  \n ").isEmpty());
        assertTrue(TeacherChildChunkSplitter.split(null).isEmpty());
    }

    @Test
    void paragraphsPackUpToTheCapWithoutSplittingShortOnes() {
        String paragraph = "正弦定理 a/sinA=b/sinB=c/sinC=2R。";
        String text = (paragraph + "\n\n").repeat(40);
        List<String> chunks = TeacherChildChunkSplitter.split(text);
        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= TeacherChildChunkSplitter.MAX_CHUNK_CHARS);
        }
        String rejoined = String.join("\n", chunks).replace("\n\n", "\n");
        assertEquals(0, countOccurrences(rejoined, paragraph) - countOccurrences(text, paragraph));
    }

    @Test
    void singleLongParagraphSplitsAtSentenceBoundary() {
        String sentence = "辅助角公式把 asinx 加 bcosx 合并成一个正弦函数，从而方便求最值、周期和对称轴。";
        String text = sentence.repeat(15);
        assertTrue(text.length() > TeacherChildChunkSplitter.MAX_CHUNK_CHARS);
        List<String> chunks = TeacherChildChunkSplitter.split(text);
        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= TeacherChildChunkSplitter.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void tinyTrailingPieceMergesIntoPreviousChunk() {
        String head = "解三角形最值的常用手段是把边角条件化成单一变量的三角函数，再利用取等条件求范围。".repeat(10);
        String tail = "短结尾。";
        List<String> chunks = TeacherChildChunkSplitter.split(head + "\n\n" + tail);
        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.get(chunks.size() - 1).endsWith(tail));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count += 1;
            index += needle.length();
        }
        return count;
    }
}
