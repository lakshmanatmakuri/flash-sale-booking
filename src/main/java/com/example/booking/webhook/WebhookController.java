package com.example.booking.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * The RAW body is bound as a String: the signature covers the exact bytes, so we must not re-serialize anything.
     * 200 for processed, duplicate and ignored (tell the provider to stop retrying). 401 for a bad signature. 5xx makes
     * the provider retry, which is exactly right if our transaction failed.
     */
    @PostMapping(value = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> payments(@RequestBody String rawBody,
                                        @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {
        return Map.of("status", webhookService.handle(rawBody, signature));
    }
}