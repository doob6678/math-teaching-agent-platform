package com.doob.mathagent.securityrisk.controller;

import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capability token API for high-value operation preparation.
 */
@RestController
public class CapabilityTokenController {

    private final CapabilityTokenService tokenService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates a capability token controller.
     *
     * @param tokenService token service
     * @param subjectResolver backend subject resolver
     */
    public CapabilityTokenController(
            CapabilityTokenService tokenService,
            RequestSubjectResolver subjectResolver) {
        this.tokenService = tokenService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Applies a short-lived one-time capability token.
     *
     * @param request apply request
     * @param httpRequest HTTP request
     * @return capability token response
     */
    @PostMapping("/api/security/capabilities")
    public CapabilityTokenResponse apply(
            @Valid @RequestBody CapabilityTokenApplyRequest request,
            HttpServletRequest httpRequest) {
        try {
            return tokenService.apply(request, subjectResolver.resolve(httpRequest));
        } catch (IllegalArgumentException exception) {
            throw statusException(exception);
        }
    }

    /**
     * Maps capability policy denials to explicit HTTP errors instead of leaking as server failures.
     */
    private static ResponseStatusException statusException(IllegalArgumentException exception) {
        HttpStatus status = exception.getMessage().contains("subject not allowed")
                ? HttpStatus.FORBIDDEN
                : HttpStatus.BAD_REQUEST;
        return new ResponseStatusException(status, exception.getMessage(), exception);
    }
}
