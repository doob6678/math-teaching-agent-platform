package com.doob.mathagent.student.service;

import com.doob.mathagent.agent.service.AiChatStreamDelta;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import java.util.List;

/** Bridges real provider deltas and fully formed cards into the explanation SSE workflow. */
@FunctionalInterface
public interface StudentExplanationAiStreamListener {
    StudentExplanationAiStreamListener NOOP = (delta, cards) -> { };

    /** A card is supplied only after its complete JSON object has arrived from the provider. */
    void onDelta(AiChatStreamDelta delta, List<StudentExplanationResponse.ExplanationCard> cards);
}
