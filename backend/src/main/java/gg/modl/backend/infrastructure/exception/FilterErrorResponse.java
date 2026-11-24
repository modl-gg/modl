package gg.modl.backend.infrastructure.exception;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

public final class FilterErrorResponse {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FilterErrorResponse() {
    }

    public static void writeJson(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        write(response, status, body(status, message));
    }

    public static void writeJson(HttpServletResponse response, HttpStatus status, String message, String code) throws IOException {
        Map<String, Object> body = body(status, message);
        body.put("code", code);
        write(response, status, body);
    }

    public static void writeJsonRetryingAfter(HttpServletResponse response, HttpStatus status, String message, long retryAfterSeconds)
        throws IOException {
        Map<String, Object> body = body(status, message);
        body.put("retryAfterSeconds", retryAfterSeconds);
        write(response, status, body);
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", status.value());
        body.put("error", message);
        body.put("message", message);
        body.put("code", HttpErrorMapping.machineCode(status));
        return body;
    }

    private static void write(HttpServletResponse response, HttpStatus status, Map<String, Object> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
