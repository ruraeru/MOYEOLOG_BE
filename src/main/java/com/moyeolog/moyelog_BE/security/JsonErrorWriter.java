package com.moyeolog.moyelog_BE.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 필터 단계(컨트롤러 밖)에서 나가는 401/403 을 GlobalExceptionHandler 와 같은
 * {error, message, status, timestamp} 형식으로 쓴다.
 */
final class JsonErrorWriter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private JsonErrorWriter() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + escape(status.getReasonPhrase())
                + "\",\"message\":\"" + escape(message)
                + "\",\"status\":" + status.value()
                + ",\"timestamp\":\"" + LocalDateTime.now().format(TIMESTAMP) + "\"}");
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
