package us.minitv.paccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transactional email via Resend, ported from account/mail.py.
 *
 * <p>The API key stays in {@code RESEND_API_KEY}; the sender must be an address
 * on a domain verified in that Resend account. An empty {@code RESEND_FROM}
 * means the sender is not chosen yet, so the call fails closed instead of
 * guessing a domain that belongs to another product.</p>
 */
@Service
public class PAccountMailService {

    private static final String RESEND_API = "https://api.resend.com/emails";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Resend rejected the message, or the sender is not configured. */
    public static class MailException extends RuntimeException {
        public MailException(String message) {
            super(message);
        }
    }

    @Value("${RESEND_API_KEY:}")
    private String resendApiKey;

    @Value("${RESEND_FROM:}")
    private String resendFrom;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Send a one-time code. The plaintext is only in this message, never logged. */
    public void sendVerificationCode(String to, String code, String purpose) {
        String subject = switch (purpose == null ? "" : purpose) {
            case "register" -> "Your MiniTV verification code";
            case "login" -> "Your MiniTV sign-in code";
            case "bind" -> "Your MiniTV email binding code";
            default -> "Your MiniTV verification code";
        };
        String text = "Your MiniTV code is " + code
                + ". It expires in 10 minutes. If you did not request it, ignore this email.";
        String html = "<p>Your MiniTV code is <strong>" + code + "</strong>.</p>"
                + "<p>It expires in 10 minutes. If you did not request it, ignore this email.</p>";
        sendEmail(to, subject, text, html);
    }

    /** Python {@code send_email}: returns the Resend message id. */
    public String sendEmail(String to, String subject, String text, String html) {
        String apiKey = resendApiKey == null ? "" : resendApiKey.strip();
        String sender = resendFrom == null ? "" : resendFrom.strip();
        if (apiKey.isEmpty() || sender.isEmpty()) {
            throw new MailException("RESEND_API_KEY and RESEND_FROM must both be set");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", sender);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        payload.put("text", text);
        if (html != null && !html.isEmpty()) {
            payload.put("html", html);
        }
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new MailException("cannot serialise the mail payload");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(RESEND_API))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "minitv-mail")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailException("resend is unreachable");
        } catch (Exception ex) {
            throw new MailException("resend is unreachable");
        }
        if (response.statusCode() >= 400) {
            throw new MailException("resend rejected the message (" + response.statusCode() + ")");
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw new MailException("resend returned no message id");
        }
        String messageId = parsed != null && parsed.isObject() ? text(parsed, "id") : null;
        if (messageId == null || messageId.isEmpty()) {
            throw new MailException("resend returned no message id");
        }
        return messageId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
