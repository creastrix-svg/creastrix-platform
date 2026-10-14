package com.creastrix.platform.authentication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** Terminal default-path error rendering, independent of authentication or error details. */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class OpaqueErrorController implements ErrorController {
    private static final String BODY = "{\"error\":\"request_not_completed\"}";

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        Object original = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = original instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        // Discard partial content, not unrelated headers/cookies or the selected response channel.
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Length", null);
        ServletOutputStream output;
        try {
            output = response.getOutputStream();
        }
        catch (IllegalStateException writerSelected) {
            // A previously selected writer retains its charset; do not invent a byte length.
            response.getWriter().write(BODY);
            return;
        }
        output.write(BODY.getBytes(StandardCharsets.UTF_8));
    }
}
