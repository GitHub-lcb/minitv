package us.minitv.pcommerce.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.common.ApiException;
import us.minitv.pcommerce.service.PCommerceBunnyCallbackService;
import us.minitv.pcommerce.support.PCommerceRequests;
import us.minitv.pcommerce.support.PCommerceUploadRejected;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bunny transcode callback: POST /v1/bunny/callback. */
@RestController
public class PCommerceBunnyController {

    private final PCommerceBunnyCallbackService callbacks;

    public PCommerceBunnyController(PCommerceBunnyCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/v1/bunny/callback")
    public Map<String, Object> callback(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String signature = request.getHeader("X-Bunny-Signature");
        JsonNode payload;
        try {
            payload = PCommerceRequests.readJson(body);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "json required");
        }
        // Accept our local shape ({"video_id", "status"}) and Bunny's native
        // webhook shape ({"VideoGuid", "Status": 0..5}).
        String videoId = PCommerceRequests.text(payload, "video_id");
        if (videoId == null || videoId.isEmpty()) {
            videoId = PCommerceRequests.text(payload, "VideoGuid");
        }
        if (videoId == null || videoId.isEmpty()) {
            throw new ApiException(400, "video id required");
        }
        String outcome;
        JsonNode rawStatus = payload.has("status")
                ? payload.get("status")
                : (payload.has("Status") ? payload.get("Status") : null);
        // Python's isinstance(raw_status, int) also catches booleans (True == 1).
        boolean numericStatus = rawStatus != null && !rawStatus.isNull()
                && (rawStatus.isIntegralNumber() || rawStatus.isBoolean());
        if (numericStatus) {
            long status = rawStatus.isBoolean()
                    ? (rawStatus.asBoolean() ? 1L : 0L) : rawStatus.asLong();
            if (status < 4) {
                Map<String, Object> ignored = new LinkedHashMap<>();
                ignored.put("ignored", true);
                ignored.put("status", rawStatus.isBoolean() ? rawStatus.asBoolean() : status);
                return ignored;
            }
            outcome = status >= 5 ? "failed" : "finished";
        } else {
            String text = rawStatus == null || rawStatus.isNull() ? "" : rawStatus.asText();
            String lowered = text.toLowerCase(Locale.ROOT);
            outcome = "failed".equals(lowered) || "error".equals(lowered) ? "failed" : "finished";
        }
        try {
            return callbacks.applyCallback(body, signature == null ? "" : signature, videoId, outcome);
        } catch (PCommerceUploadRejected ex) {
            int code = "bad signature".equals(ex.reason()) ? 401 : 400;
            throw new ApiException(code, ex.reason());
        }
    }
}
