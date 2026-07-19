package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * Standard MCP prompt descriptor exposed through prompts/list.
 *
 * @param name stable prompt name used by MCP clients
 * @param title short display title
 * @param description concise prompt purpose
 * @param allowedProfiles backend profiles allowed to read this prompt
 * @param arguments optional argument descriptors accepted by prompts/get
 */
public record McpPromptDescriptor(
        String name,
        String title,
        String description,
        List<String> allowedProfiles,
        List<Argument> arguments) {

    /**
     * One prompt argument descriptor.
     *
     * @param name argument name
     * @param title display title
     * @param description argument meaning
     * @param required whether the argument is required
     */
    public record Argument(
            String name,
            String title,
            String description,
            boolean required) {
    }
}
