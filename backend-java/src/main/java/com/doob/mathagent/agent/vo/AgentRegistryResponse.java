package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Backend-governed agent marketplace catalog.  It exposes capabilities, never prompts, credentials, or raw policy
 * internals, so the React plaza can discover agents without becoming an authorization source.
 */
public record AgentRegistryResponse(List<Item> agents) {

    /** One agent card and its client-safe execution contract. */
    public record Item(
            String code,
            String name,
            String category,
            String description,
            List<String> allowedToolScopes,
            List<String> allowedDataScopes,
            String inputHint,
            String outputArtifactType) {
    }
}
