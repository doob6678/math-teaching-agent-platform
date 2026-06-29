package com.doob.mathagent.infrastructure.security;

/**
 * Authenticated request subject resolved on the backend.
 *
 * @param tenantId tenant id from server-side session or trusted local fallback
 * @param subjectType role used by authorization rules, such as student, teacher, admin, or anonymous
 * @param subjectId server-side user id; never taken from business request body
 * @param deviceId device id used for rate-limit buckets and audit
 */
public record RequestSubject(
        String tenantId,
        String subjectType,
        String subjectId,
        String deviceId) {

    /**
     * Returns a normalized subject with safe defaults.
     *
     * @return normalized subject
     */
    public RequestSubject normalize() {
        return new RequestSubject(
                textOrDefault(tenantId, "default"),
                textOrDefault(subjectType, "anonymous").toLowerCase(),
                blankToNull(subjectId),
                textOrDefault(deviceId, "unknown-device"));
    }

    /**
     * Builds an anonymous subject for unauthenticated HTTP requests.
     *
     * @param tenantId tenant id fallback
     * @param deviceId device id fallback
     * @return anonymous subject
     */
    public static RequestSubject anonymous(String tenantId, String deviceId) {
        return new RequestSubject(tenantId, "anonymous", null, deviceId).normalize();
    }

    /**
     * Builds a local test subject used only when a controller is called without an HTTP request.
     *
     * @return local student subject
     */
    public static RequestSubject localStudent() {
        return new RequestSubject("default", "student", "local-student", "local-device");
    }

    /**
     * Builds a local teacher subject for controller tests that need teacher-only behavior.
     *
     * @return local teacher subject
     */
    public static RequestSubject localTeacher() {
        return new RequestSubject("default", "teacher", "local-teacher", "local-device");
    }

    /**
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Returns stripped text or null when blank.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
