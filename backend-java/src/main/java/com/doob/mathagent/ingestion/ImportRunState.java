package com.doob.mathagent.ingestion;

import java.util.EnumSet;
import java.util.Set;

/** Recoverable state machine for a whole import; validation has its own independent state in persistence. */
public enum ImportRunState {
    CREATED,
    PARSING_ALL_FILES,
    PAIRING_AND_DEDUPLICATING,
    INDEXING,
    COMPLETED,
    PARTIALLY_FAILED,
    FAILED;

    /** Allows only forward work, or terminal failure from an active stage so checkpoint recovery remains honest. */
    public ImportRunState transitionTo(ImportRunState next) {
        Set<ImportRunState> allowed = switch (this) {
            case CREATED -> EnumSet.of(PARSING_ALL_FILES, FAILED);
            case PARSING_ALL_FILES -> EnumSet.of(PAIRING_AND_DEDUPLICATING, PARTIALLY_FAILED, FAILED);
            case PAIRING_AND_DEDUPLICATING -> EnumSet.of(INDEXING, PARTIALLY_FAILED, FAILED);
            case INDEXING -> EnumSet.of(COMPLETED, PARTIALLY_FAILED, FAILED);
            case PARTIALLY_FAILED -> EnumSet.of(PARSING_ALL_FILES, PAIRING_AND_DEDUPLICATING, INDEXING, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(ImportRunState.class);
        };
        if (next == null || !allowed.contains(next)) {
            throw new IllegalStateException("Illegal import state transition from " + this + " to " + next);
        }
        return next;
    }
}
