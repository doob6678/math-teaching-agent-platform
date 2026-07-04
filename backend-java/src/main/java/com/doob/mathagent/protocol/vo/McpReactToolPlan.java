package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * ReAct execution guidance returned as MCP structuredContent for external agents.
 *
 * @param style planning style name
 * @param parallelizable whether any safe group can run concurrently
 * @param groups ordered action groups
 * @param answerPolicy concise policy the external agent should follow before high-value execution
 */
public record McpReactToolPlan(
        String style,
        boolean parallelizable,
        List<Group> groups,
        String answerPolicy) {

    /**
     * One ordered ReAct group.
     *
     * @param groupId stable group id
     * @param mode parallel or sequential
     * @param dependsOn previous group ids required before this group
     * @param thought model-facing planning note
     * @param actions tool actions available in this group
     * @param observation expected observation merge rule
     */
    public record Group(
            String groupId,
            String mode,
            List<String> dependsOn,
            String thought,
            List<Action> actions,
            String observation) {
    }

    /**
     * One tool action descriptor inside a ReAct group.
     *
     * @param toolScope backend policy scope
     * @param toolName MCP tool name or internal action name
     * @param enabled whether the action can be used by the external agent
     * @param reason concise policy reason
     */
    public record Action(
            String toolScope,
            String toolName,
            boolean enabled,
            String reason) {
    }
}
