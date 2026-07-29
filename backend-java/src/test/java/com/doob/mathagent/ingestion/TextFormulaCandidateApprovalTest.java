package com.doob.mathagent.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextFormulaCandidateApprovalTest {
    @Test
    void approvesNonEmptyPdfTextWithStableWhitespaceInsensitiveFingerprint() {
        TextFormulaCandidateApproval compact = TextFormulaCandidateApproval.approve("source-hash", 1, "1", "1.  求  x^2");
        TextFormulaCandidateApproval spaced = TextFormulaCandidateApproval.approve("source-hash", 1, "1", "1.\n求   x^2");

        assertEquals("1. 求 x^2", compact.normalizedText());
        assertEquals(compact.fingerprint(), spaced.fingerprint());
        assertEquals(TextFormulaCandidateApproval.STORAGE_APPROVED_STATUS, "AUTO_APPROVED_TEXT_FORMULA");
    }

    @Test
    void keepsDifferentSourceCopiesAuditableInsteadOfCrossFileMergingThem() {
        TextFormulaCandidateApproval first = TextFormulaCandidateApproval.approve("source-a", 1, "1", "1. 求 x");
        TextFormulaCandidateApproval second = TextFormulaCandidateApproval.approve("source-b", 1, "1", "1. 求 x");

        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void rejectsAnEmptyCandidate() {
        assertThrows(IllegalArgumentException.class,
                () -> TextFormulaCandidateApproval.approve("source-hash", 1, "1", " \n\t "));
    }
}
