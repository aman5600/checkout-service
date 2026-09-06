package com.example.checkout.api;

import com.example.checkout.domain.OrderStatus;
import com.example.checkout.service.CheckoutService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider callbacks. Always answers 200, including for duplicates, unknown
 * orders, and events that arrive after the job already resolved the order --
 * anything else just makes the provider retry and manufacture more duplicates.
 * <p>
 * No signature verification here; see the design note.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final CheckoutService checkout;

    public WebhookController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /** Correlated on orderId, never on the provider's own reference. */
    public record PaymentEvent(String eventId, String orderId, String status, String paymentRef) {
    }

    @PostMapping("/webhooks/payment")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody PaymentEvent event) {
        OrderStatus reported;
        try {
            reported = OrderStatus.valueOf(event.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("webhook event={} carried unusable status {} -- acked and dropped", event.eventId(), event.status());
            return ResponseEntity.ok(Map.of("applied", false, "reason", "unrecognised status"));
        }

        boolean applied = checkout.applyWebhook(event.orderId(), reported, event.paymentRef());
        return ResponseEntity.ok(Map.of("applied", applied));
    }
}
