package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import org.junit.jupiter.api.Test;

class QuestionBankSearchTextEnglishConceptTest {

    @Test
    void mapsEnglishQuadraticMinimumLanguageToStrictChineseTopics() {
        String request = "Solve f(x)=x^2-4x+3 and explain the minimum using vertex form. "
                + "Master completing the square and quadratic minima.";

        assertThat(QuestionBankSearchText.candidateQueries(request))
                .contains("二次函数", "最值");
        assertThat(QuestionBankSearchText.specificTopicTerms(request))
                .contains("二次函数", "最值");
    }
}
