package com.doob.mathagent.teaching;

/**
 * Publication policy evaluated after deterministic and AI quality checks complete.
 *
 * <p>Keeping this decision in the durable Java domain layer prevents a Python model or a browser client from
 * publishing a handout merely because it claims that review succeeded.</p>
 */
public enum TeachingReviewPolicy {
    /** Publish only after the automatic quality gate succeeds. */
    AUTO_PUBLISH,
    /** Preserve the reviewed draft and wait for a teacher or administrator decision. */
    HUMAN_APPROVAL,
    /** Preserve the draft for later editing without creating a publishable version. */
    DRAFT_ONLY;

    /** Reads the deployment policy without embedding a tenant/operator decision in workflow code. */
    public static TeachingReviewPolicy fromEnvironment() {
        String configured = System.getenv("MATH_AGENT_TEACHING_REVIEW_POLICY");
        if (configured == null || configured.isBlank()) {
            return AUTO_PUBLISH;
        }
        try {
            return valueOf(configured.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported MATH_AGENT_TEACHING_REVIEW_POLICY: " + configured, exception);
        }
    }

    /**
     * Calculates the one durable state following the automatic quality gate.
     *
     * @param qualityPassed whether structural and source checks passed
     * @return durable task status; a failed gate is never publishable
     */
    public TeachingTaskStatus statusAfterQualityGate(boolean qualityPassed) {
        if (!qualityPassed) {
            return TeachingTaskStatus.FAILED;
        }
        return switch (this) {
            case AUTO_PUBLISH -> TeachingTaskStatus.COMPLETED;
            case HUMAN_APPROVAL -> TeachingTaskStatus.WAITING_REVIEW;
            case DRAFT_ONLY -> TeachingTaskStatus.DRAFT_ONLY;
        };
    }
}
