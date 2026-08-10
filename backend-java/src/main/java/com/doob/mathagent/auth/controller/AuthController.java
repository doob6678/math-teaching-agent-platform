package com.doob.mathagent.auth.controller;

import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.dto.TeacherAccountProvisionRequest;
import com.doob.mathagent.auth.service.AuthService;
import com.doob.mathagent.auth.vo.LoginResponse;
import com.doob.mathagent.auth.vo.TeacherAccountProvisionResponse;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication API backed by Sa-Token sessions.
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates an auth controller.
     *
     * @param authService authentication service
     */
    public AuthController(AuthService authService, RequestSubjectResolver subjectResolver) {
        this.authService = authService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Logs in with backend-validated username and password.
     *
     * @param request login request
     * @return backend session metadata; the browser session is written as an HttpOnly cookie
     */
    @PostMapping("/api/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            return authService.login(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /**
     * Validates the current backend token and returns the trusted session identity.
     *
     * @return current session identity
     */
    @GetMapping("/api/auth/session")
    public LoginResponse currentSession() {
        try {
            return authService.currentSession();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /** Logs out the server-side session and clears the HttpOnly authentication cookie. */
    @PostMapping("/api/auth/logout")
    public void logout() {
        authService.logout();
    }

    /**
     * Registers a student account and creates an HttpOnly backend session cookie.
     *
     * @param request registration request
     * @return backend session metadata; the browser session is written as an HttpOnly cookie
     */
    @PostMapping("/api/auth/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        try {
            return authService.register(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Lets a trusted administrator create a teacher account able to upload private teaching PDFs and own MCP keys.
     *
     * @param request credentials for the new teacher; tenant selection is ignored by the service
     * @param httpRequest trusted backend session source
     * @return created account metadata without password material or a session token for the new teacher
     */
    @PostMapping("/api/auth/teachers")
    public TeacherAccountProvisionResponse createTeacher(
            @Valid @RequestBody TeacherAccountProvisionRequest request,
            HttpServletRequest httpRequest) {
        try {
            return TeacherAccountProvisionResponse.from(
                    authService.provisionTeacher(request, subjectResolver.resolve(httpRequest)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }
}
