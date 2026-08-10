package com.doob.mathagent.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.session.SaSession;
import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.dto.TeacherAccountProvisionRequest;
import com.doob.mathagent.auth.vo.LoginResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Authentication service that creates Sa-Token sessions from backend account data.
 */
@Service
public class AuthService {

    private final LocalAccountStore accountStore;
    private final PasswordHashService passwordHashService;
    private final String registrationTenantId;

    /**
     * Creates an auth service.
     *
     * @param accountStore account lookup store
     */
    @Autowired
    public AuthService(
            LocalAccountStore accountStore,
            PasswordHashService passwordHashService,
            @Value("${math-agent.auth.registration-tenant-id:default}") String registrationTenantId) {
        this.accountStore = accountStore;
        this.passwordHashService = passwordHashService;
        this.registrationTenantId = normalizeTenant(registrationTenantId);
    }

    /**
     * Compatibility constructor for focused unit tests. Production construction always uses the configured tenant.
     */
    public AuthService(LocalAccountStore accountStore, PasswordHashService passwordHashService) {
        this(accountStore, passwordHashService, "default");
    }

    /**
     * Logs in a local account and stores trusted identity fields in the backend session.
     *
     * @param request login request
     * @return non-sensitive session metadata; the raw session token remains in the HttpOnly cookie
     */
    public LoginResponse login(LoginRequest request) {
        LocalAccount account = accountStore.findByUsername(request.username())
                .filter(found -> passwordHashService.matches(request.password(), found.password()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        return loginAccount(account);
    }

    /**
     * Registers a student account and immediately creates a backend session.
     *
     * @param request registration request
     * @return non-sensitive session metadata; the raw session token remains in the HttpOnly cookie
     */
    public LoginResponse register(RegisterRequest request) {
        LocalAccount account = accountStore.createStudent(
                request.username(),
                passwordHashService.encode(request.password()),
                registrationTenantId);
        return loginAccount(account);
    }

    /**
     * Creates a teacher account in the administrator's tenant without logging in as that teacher.
     *
     * <p>The request's tenant is deliberately ignored. Role assignment and tenant ownership are management actions,
     * so both are derived from the already authenticated administrator rather than from a browser payload.</p>
     *
     * @param request teacher credentials to create
     * @param administrator trusted session subject
     * @return newly persisted teacher account
     */
    public LocalAccount provisionTeacher(TeacherAccountProvisionRequest request, RequestSubject administrator) {
        RequestSubject normalizedAdmin = administrator == null
                ? RequestSubject.anonymous("default", "unknown-device")
                : administrator.normalize();
        if (!"admin".equals(normalizedAdmin.subjectType())
                || normalizedAdmin.subjectId() == null
                || normalizedAdmin.subjectId().isBlank()) {
            throw new IllegalArgumentException("Teacher provisioning requires an administrator session");
        }
        return accountStore.createTeacher(
                request.username(),
                passwordHashService.encode(request.password()),
                normalizedAdmin.tenantId());
    }

    /**
     * Returns the current backend session when the submitted Sa-Token is still valid.
     */
    public LoginResponse currentSession() {
        if (!StpUtil.isLogin()) {
            throw new IllegalArgumentException("Session is not authenticated");
        }
        SaSession session = StpUtil.getSession();
        String userId = StpUtil.getLoginIdAsString();
        String username = stringValue(session.get("username"), userId);
        String role = stringValue(session.get("role"), "student");
        String tenantId = stringValue(session.get("tenantId"), "default");
        return new LoginResponse(userId, username, role, tenantId);
    }

    /** Invalidates the current server-side session; the browser cookie is cleared by Sa-Token. */
    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    /**
     * Creates a Sa-Token session from a trusted account record.
     */
    private static LoginResponse loginAccount(LocalAccount account) {
        StpUtil.login(account.userId());
        StpUtil.getSession().set("tenantId", account.tenantId());
        StpUtil.getSession().set("role", account.role());
        StpUtil.getSession().set("username", account.username());
        return new LoginResponse(account.userId(), account.username(), account.role(), account.tenantId());
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    /** Public registration cannot choose a tenant; the configured tenant is the sole backend authority. */
    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }
}
