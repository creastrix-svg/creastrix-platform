package com.creastrix.platform.authentication;

import java.io.IOException;

import com.creastrix.platform.observability.Diagnostics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous provider recovery, independent of Q and ordinary login intent/admission. */
@RestController
@ConditionalOnProperty(name = "creastrix.auth.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class CognitoEmailConfirmationController {
    static final String CONFIRM_PATH = "/auth/confirm-email";
    static final String RESEND_PATH = "/auth/resend-confirmation-code";
    private final AuthenticationProperties properties;
    private final CognitoEmailConfirmationService service;

    public CognitoEmailConfirmationController(AuthenticationProperties properties, CognitoEmailConfirmationService service) {
        this.properties = properties;
        this.service = service;
    }

    @PostMapping(CONFIRM_PATH)
    public void confirm(Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
        recover(CognitoEmailConfirmationService.Operation.CONFIRM, authentication, request, response);
    }
    @PostMapping(RESEND_PATH)
    public void resend(Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
        recover(CognitoEmailConfirmationService.Operation.RESEND, authentication, request, response);
    }

    private void recover(CognitoEmailConfirmationService.Operation operation, Authentication authentication,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Defense in depth. The early security filter rejects before CSRF materialization.
        if (properties.provider() != AuthenticationProperties.Provider.COGNITO
                || authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
            AuthenticationConfiguration.jsonError(response, 403);
            return;
        }
        CognitoEmailConfirmationService.Input input;
        try {
            String contentType = request.getContentType();
            MediaType type = contentType == null ? null : MediaType.parseMediaType(contentType);
            if (type == null || !"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || request.getContentLengthLong() > CognitoEmailConfirmationService.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid confirmation input");
            }
            input = CognitoEmailConfirmationService.parse(request.getInputStream()
                    .readNBytes(CognitoEmailConfirmationService.MAX_BYTES + 1), operation);
        }
        catch (IllegalArgumentException malformed) {
            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.REQUEST_BOUNDARY_REJECTED);
            AuthenticationConfiguration.jsonError(response, 400);
            return;
        }
        catch (IOException incomplete) {
            Diagnostics.mark(Diagnostics.Event.EMAIL_CONFIRMATION_RESULT, Diagnostics.Reason.PROVIDER_REQUEST_UNAVAILABLE);
            AuthenticationConfiguration.jsonError(response, 503);
            return;
        }
        var result = service.execute(operation, input);
        Diagnostics.Reason reason = switch (result) {
            case CONFIRMED -> Diagnostics.Reason.EMAIL_CONFIRMED;
            case NOT_CONFIRMED -> Diagnostics.Reason.EMAIL_NOT_CONFIRMED;
            case CODE_REQUEST_ACCEPTED -> Diagnostics.Reason.CONFIRMATION_CODE_REQUESTED;
            case RATE_LIMITED -> Diagnostics.Reason.CONFIRMATION_RATE_LIMITED;
            case UNAVAILABLE -> Diagnostics.Reason.PROVIDER_REQUEST_UNAVAILABLE;
        };
        Diagnostics.mark(Diagnostics.Event.EMAIL_CONFIRMATION_RESULT, reason);
        switch (result) {
            case CONFIRMED -> success(response, 200, "confirmed");
            case CODE_REQUEST_ACCEPTED -> success(response, 202, "code_request_accepted");
            case NOT_CONFIRMED -> AuthenticationConfiguration.jsonError(response, 400);
            case RATE_LIMITED -> AuthenticationConfiguration.jsonError(response, 429);
            case UNAVAILABLE -> AuthenticationConfiguration.jsonError(response, 503);
        }
    }

    private static void success(HttpServletResponse response, int status, String result) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"result\":\"" + result + "\"}");
    }
}
