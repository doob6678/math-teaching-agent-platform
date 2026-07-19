package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * A2A Agent Card response describing platform capabilities without exposing execution secrets.
 *
 * @param name public agent platform name
 * @param description public capability summary
 * @param url relative A2A base URL; avoids leaking local host paths
 * @param protocolVersion documented A2A compatibility label
 * @param preferredTransport transport advertised for future A2A calls
 * @param capabilities supported A2A protocol capabilities
 * @param skills agent skills exposed as metadata only
 * @param securitySchemes supported authentication and high-value operation guards
 */
public record A2aAgentCardResponse(
        String name,
        String description,
        String url,
        String protocolVersion,
        String preferredTransport,
        Capabilities capabilities,
        List<Skill> skills,
        List<SecurityScheme> securitySchemes) {

    /**
     * A2A capability flags.
     *
     * @param streaming whether protocol streaming execution is enabled
     * @param pushNotifications whether push notifications are enabled
     * @param stateTransitionHistory whether task state history can be exposed
     */
    public record Capabilities(
            boolean streaming,
            boolean pushNotifications,
            boolean stateTransitionHistory) {
    }

    /**
     * A2A skill metadata.
     *
     * @param id stable skill id
     * @param name display name
     * @param description concise skill description
     * @param tags discovery tags
     */
    public record Skill(
            String id,
            String name,
            String description,
            List<String> tags) {
    }

    /**
     * Security scheme metadata for external clients.
     *
     * @param id stable scheme id
     * @param type authentication or authorization scheme type
     * @param description plain-language scheme description
     */
    public record SecurityScheme(
            String id,
            String type,
            String description) {
    }
}
