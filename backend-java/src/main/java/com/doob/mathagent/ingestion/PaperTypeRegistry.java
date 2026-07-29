package com.doob.mathagent.ingestion;

import java.util.EnumMap;
import java.util.Map;

/**
 * Centralizes type-specific rules and constructs citations from safe fields only.  This prevents an LLM, a source
 * path, or a database identifier from becoming visible in a handout or MCP response.
 */
public final class PaperTypeRegistry {
    private final Map<PaperType, PaperTypePolicy> policies;

    private PaperTypeRegistry(Map<PaperType, PaperTypePolicy> policies) {
        this.policies = Map.copyOf(policies);
    }

    /** Creates the supported policy set; callers cannot accidentally omit a type. */
    public static PaperTypeRegistry defaultRegistry() {
        Map<PaperType, PaperTypePolicy> policies = new EnumMap<>(PaperType.class);
        policies.put(PaperType.GAOKAO, new PaperTypePolicy(true, true, false, false));
        policies.put(PaperType.ZHONGKAO, new PaperTypePolicy(true, true, false, false));
        policies.put(PaperType.MOCK_EXAM, new PaperTypePolicy(false, true, true, false));
        policies.put(PaperType.COMPETITION, new PaperTypePolicy(true, true, false, true));
        policies.put(PaperType.GENERIC, new PaperTypePolicy(false, false, false, true));
        return new PaperTypeRegistry(policies);
    }

    /** Returns the declared type policy or rejects an accidental unspecified import. */
    public PaperTypePolicy policyFor(PaperType type) {
        if (type == null || !policies.containsKey(type)) {
            throw new IllegalArgumentException("paper type is required");
        }
        return policies.get(type);
    }

    /** Formats the public citation strictly from validated fields, never from internal metadata. */
    public PaperCitation citationFor(PaperType type, PaperMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("paper metadata is required");
        }
        String question = required(metadata.questionNumber(), "question number");
        return switch (type) {
            case GAOKAO, ZHONGKAO -> new PaperCitation(requiredYear(metadata.year()) + " "
                    + required(metadata.paperName(), "paper name") + " 第" + question + "题");
            case MOCK_EXAM -> new PaperCitation(required(metadata.institution(), "institution") + " "
                    + requiredYear(metadata.year()) + " " + required(metadata.paperName(), "paper name") + " 第" + question + "题");
            case COMPETITION -> new PaperCitation(requiredYear(metadata.year()) + " "
                    + required(metadata.paperName(), "paper name") + " 第" + question + "题");
            case GENERIC -> new PaperCitation(required(metadata.paperName(), "paper name") + " 第" + question + "题");
        };
    }

    private static String requiredYear(Integer year) {
        if (year == null || year < 1900 || year > 3000) {
            throw new IllegalArgumentException("year is required and must be valid");
        }
        return String.valueOf(year);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
