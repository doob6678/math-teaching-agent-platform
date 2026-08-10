package com.doob.mathagent.teacher.document;

import java.util.List;
import java.util.Locale;

/**
 * One visibility policy shared by document list, Agent search, and asset reads.
 *
 * <p>Every non-private scope is intentionally shared with students. Teacher uploads are therefore visible to
 * students immediately after the teacher marks them shared; only {@code TEACHER_PRIVATE} remains owner-only.</p>
 */
public final class TeacherResourceVisibilityPolicy {
    public static final List<String> STUDENT_SHARED_SCOPES = List.of(
            "TENANT_PUBLIC", "PUBLIC_TEXTBOOK", "MATH_VIP", "CLASS_AUTHORIZED");
    public static final List<String> TEACHER_SHARED_SCOPES = List.of(
            "TENANT_PUBLIC", "PUBLIC_TEXTBOOK", "MATH_VIP", "CLASS_AUTHORIZED");

    private TeacherResourceVisibilityPolicy() {
    }

    /** Validates roles that may read resource metadata through a backend-mediated tool. */
    public static boolean isReaderRole(String role) {
        String normalized = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
        return "student".equals(normalized) || "teacher".equals(normalized) || "admin".equals(normalized);
    }
}
