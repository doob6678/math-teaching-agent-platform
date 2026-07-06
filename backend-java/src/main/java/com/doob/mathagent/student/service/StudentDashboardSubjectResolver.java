package com.doob.mathagent.student.service;

import com.doob.mathagent.auth.service.LocalAccountStore;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves the subject role represented by one student dashboard response.
 */
@Service
public class StudentDashboardSubjectResolver {

    private final LocalAccountStore accountStore;

    @Autowired
    public StudentDashboardSubjectResolver(LocalAccountStore accountStore) {
        this.accountStore = accountStore;
    }

    /**
     * Resolves the backend role of the subject represented by the dashboard.
     *
     * @param query normalized dashboard query
     * @return global, viewer self role, looked-up role, or unknown when no authoritative record exists
     */
    public String resolveSubjectRole(StudentDashboardQuery query) {
        StudentDashboardQuery normalized = query.normalize();
        if (normalized.globalView()) {
            return "global";
        }
        String targetStudentId = normalized.targetStudentId();
        if (targetStudentId.equals(normalized.viewerSubjectId())) {
            return normalized.viewerRole();
        }
        return accountStore.findByUserId(targetStudentId)
                .map(account -> account.role() == null || account.role().isBlank()
                        ? "unknown"
                        : account.role().strip().toLowerCase())
                .orElse("unknown");
    }
}
