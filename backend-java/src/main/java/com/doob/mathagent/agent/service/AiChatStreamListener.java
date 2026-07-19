package com.doob.mathagent.agent.service;

/** Receives real provider text deltas while a chat completion is still running. */
@FunctionalInterface
public interface AiChatStreamListener {

    /** Invoked in provider arrival order; callers must not synthesize additional content. */
    void onDelta(AiChatStreamDelta delta);
}
