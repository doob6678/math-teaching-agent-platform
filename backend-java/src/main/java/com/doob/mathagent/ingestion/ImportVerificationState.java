package com.doob.mathagent.ingestion;

import java.util.EnumSet;
import java.util.Set;

/**
 * Verification state deliberately lives beside, but separately from, the import-processing state. A run can finish
 * parsing while remaining unpublished until deterministic rules, Golden comparison, and the task's Luna audit pass.
 */
public enum ImportVerificationState {
    NOT_STARTED,
    RULE_CHECKING,
    GOLDEN_COMPARING,
    AI_REVIEWING,
    VERIFIED,
    VERIFICATION_FAILED;

    /** Allows sequential evidence gates or a failure from any active review stage. */
    public ImportVerificationState transitionTo(ImportVerificationState next) {
        Set<ImportVerificationState> allowed = switch (this) {
            case NOT_STARTED -> EnumSet.of(RULE_CHECKING, VERIFICATION_FAILED);
            case RULE_CHECKING -> EnumSet.of(GOLDEN_COMPARING, VERIFICATION_FAILED);
            case GOLDEN_COMPARING -> EnumSet.of(AI_REVIEWING, VERIFICATION_FAILED);
            case AI_REVIEWING -> EnumSet.of(VERIFIED, VERIFICATION_FAILED);
            case VERIFIED, VERIFICATION_FAILED -> EnumSet.noneOf(ImportVerificationState.class);
        };
        if (next == null || !allowed.contains(next)) {
            throw new IllegalStateException("Illegal verification state transition from " + this + " to " + next);
        }
        return next;
    }
}
