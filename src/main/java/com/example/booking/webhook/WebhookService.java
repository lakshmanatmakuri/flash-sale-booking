package com.example.booking.webhook;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.payment.provider.PaymentProvider;
import com.example.booking.refund.RefundExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookProperties props;
    private final ObjectMapper mapper;
    private final PaymentProvider provider;
    private final WebhookProcessor processor;
    private final RefundExecutor refundExecutor;

    public String handle(String rawBody, String signatureHeader) {
        // 1) Authenticate BEFORE parsing or touching anything.
        WebhookSignature.verify(props.secret(), signatureHeader, rawBody, props.toleranceSeconds(), Instant.now());

        // 2) Parse and validate the shape.
        WebhookEvent event = parse(rawBody);

        // 3) One transaction: dedupe marker + business effects. A failure rolls both back, so the provider's retry works.
        WebhookProcessor.Outcome outcome = processor.process(provider.name(), event, UUID.fromString(event.data().reference()), rawBody);

        // 4) The provider refund runs AFTER commit, never inside the webhook transaction. If we crash here, the
        //    REQUESTED refund row is durable and the recovery job finishes it. The webhook still gets its 200.
        outcome.refundToExecute().ifPresent(refundId -> {
            try {
                refundExecutor.execute(refundId);
            } catch (RuntimeException e) {
                log.error("Refund {} not completed inside the webhook call; recovery will retry", refundId, e);
            }
        });
        return outcome.result();
    }

    private WebhookEvent parse(String rawBody) {
        try {
            WebhookEvent e = mapper.readValue(rawBody, WebhookEvent.class);
            if (e.id() == null || e.id().isBlank() || e.type() == null || e.data() == null || e.data().reference() == null) {
                throw new BadRequestException("Webhook needs id, type and data.reference");
            }
            UUID.fromString(e.data().reference());
            return e;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BadRequestException("Malformed webhook payload");
        }
    }
}
