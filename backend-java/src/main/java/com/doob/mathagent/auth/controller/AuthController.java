package com.doob.mathagent.auth.controller;

import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.service.AuthService;
import com.doob.mathagent.auth.vo.LoginResponse;
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

    /**
     * Creates an auth controller.
     *
     * @param authService authentication service
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Logs in with backend-validated username and password.
     *
     * @param request login request
     * @return issued session token
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

    /**
     * Registers a student account and returns a backend session token.
     *
     * @param request registration request
     * @return issued session token
     */
    @PostMapping("/api/auth/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        try {
            return authService.register(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
