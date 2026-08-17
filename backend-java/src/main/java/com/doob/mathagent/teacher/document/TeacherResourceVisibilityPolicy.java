package com.doob.mathagent.teacher.document;

import java.util.List;
import java.util.Locale;

/**
 * One visibility policy shared by document list, Agent search, and asset reads.
 *
 * <p>{@code TEACHER_SHARED} is the deliberate tenant-wide staff library: it is discoverable by teachers and
 * administrators in the same tenant, but is not a student-facing publication scope. Other shared scopes remain
 * available to students when a teacher explicitly publishes material for that audience.</p>
 */
public final class TeacherResourceVisibilityPolicy {
    public static final List<String> STUDENT_SHARED_SCOPES = List.of(
            "TENANT_PUBLIC", "PUBLIC_TEXTBOOK", "MATH_VIP", "CLASS_AUTHORIZED");
    public static final List<String> TEACHER_SHARED_SCOPES = List.of(
            "TEACHER_SHARED", "TENANT_PUBLIC", "PUBLIC_TEXTBOOK", "MATH_VIP", "CLASS_AUTHORIZED");

    private TeacherResourceVisibilityPolicy() {
    }

    /** Validates roles that may read resource metadata through a backend-mediated tool. */
    public static boolean isReaderRole(String role) {
        String normalized = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
        return "student".equals(normalized) || "teacher".equals(normalized) || "admin".equals(normalized);
    }
}
