package com.doob.mathagent.teaching.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Spring AI adapter reserved for handout drafting.
 *
 * <p>The bean is created only when Spring AI has a ChatClient.Builder. This keeps local tests and offline development
 * independent from OpenAI credentials while still using Spring AI as the Java integration point.</p>
 */
@Service
@ConditionalOnBean(ChatClient.Builder.class)
public class SpringAiHandoutDraftService {

    private final ChatClient chatClient;

    /**
     * Builds a reusable Spring AI chat client from the official builder.
     *
     * @param chatClientBuilder Spring AI chat client builder
     */
    public SpringAiHandoutDraftService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Drafts teacher-facing handout notes from a learning goal and retrieved evidence.
     *
     * @param learningGoal user learning goal
     * @param evidenceText retrieved textbook or private knowledge evidence
     * @return generated teacher notes
     */
    public String draftTeacherNotes(String learningGoal, String evidenceText) {
        return chatClient.prompt()
                .user("""
                        Write concise teacher handout notes in Chinese.
                        Learning goal: %s
                        Evidence: %s
                        Avoid AI-style wording and keep the explanation classroom-ready.
                        """.formatted(learningGoal, evidenceText))
                .call()
                .content();
    }
}
