package com.doob.mathagent.ingestion;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionNumberDetectorDuplicateProtectionTest {
    @Test
    void acceptsOneTopLevelNumberPerPageWhenTextLayerRepeatsIt() {
        Set<String> pageQuestionNumbers = new HashSet<>();
        String first = QuestionNumberDetector.topLevelNumber("3. 已知函数 f(x)").orElseThrow();
        String echoed = QuestionNumberDetector.topLevelNumber("3. 已知函数 f(x)").orElseThrow();

        assertTrue(pageQuestionNumbers.add(first));
        assertFalse(pageQuestionNumbers.add(echoed));
    }
}
