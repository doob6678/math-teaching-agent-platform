package com.doob.mathagent.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the documented ingestion command without coupling the domain to Spring's web/server startup arguments.
 * Keeping parsing here lets the Docker command and tests share one explicit contract.
 */
public record IngestionCommandArguments(String inputRoot, PaperType paperType, String model) {
    /** Exact command name exposed by the batch entry point. */
    public static final String COMMAND = "gaokao:ingest-and-verify";
    private static final String INPUT_OPTION = "input";
    private static final String PAPER_TYPE_OPTION = "paper-type";
    private static final String MODEL_OPTION = "model";

    /**
     * Parses command-line tokens in either {@code --name value} or {@code --name=value} form.
     *
     * @param arguments raw non-shell-expanded argument tokens
     * @return validated batch configuration
     */
    public static IngestionCommandArguments parse(List<String> arguments) {
        if (arguments == null || arguments.isEmpty() || !COMMAND.equals(arguments.getFirst())) {
            throw new IllegalArgumentException("command must be " + COMMAND);
        }
        Map<String, String> options = parseOptions(arguments.subList(1, arguments.size()));
        String input = required(options, INPUT_OPTION);
        String typeValue = required(options, PAPER_TYPE_OPTION);
        PaperType type;
        try {
            type = PaperType.valueOf(typeValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown paper-type: " + typeValue, exception);
        }
        return new IngestionCommandArguments(input, type, optional(options, MODEL_OPTION));
    }

    /** Turns long options into a single map and rejects ambiguous duplicate flags. */
    private static Map<String, String> parseOptions(List<String> tokens) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token == null || !token.startsWith("--")) {
                throw new IllegalArgumentException("expected --option, received: " + token);
            }
            String withoutPrefix = token.substring(2);
            int equalsIndex = withoutPrefix.indexOf('=');
            String name = equalsIndex >= 0 ? withoutPrefix.substring(0, equalsIndex) : withoutPrefix;
            String value;
            if (equalsIndex >= 0) {
                value = withoutPrefix.substring(equalsIndex + 1);
            } else {
                if (index + 1 >= tokens.size() || tokens.get(index + 1).startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                value = tokens.get(++index);
            }
            if (options.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate option --" + name);
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = optional(options, name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }

    private static String optional(Map<String, String> options, String name) {
        String value = options.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
