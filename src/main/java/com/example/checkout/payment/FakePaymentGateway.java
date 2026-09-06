package com.example.checkout.payment;

import com.example.checkout.config.CheckoutProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A stand-in payment provider, so every branch of the design is reachable
 * without a real account. It behaves like the real thing in the ways that
 * matter: it is idempotent on the order id, it settles on its own schedule,
 * and it delivers webhooks over real HTTP whenever it feels like it.
 * <p>
 * Pick a behaviour per order with the {@code scenario} field on the checkout
 * request:
 *
 * <pre>
 *   FAST      settles at 0.3s, webhook at 0.4s   -> webhook wins the race
 *   SLOW      settles at 1s,   webhook at 10s    -> job wins, late webhook is a no-op
 *   NEVER     settles at 1s,   no webhook ever   -> job wins
 *   STUCK     never settles                      -> job gives up and FAILS a live payment
 *   DECLINED  settles failed at 0.3s, webhook    -> ordinary decline
 *   REJECT    initiate is rejected outright      -> resolved without ever waiting
 *   UNKNOWN   initiate "times out", settles paid -> the money case: unknown != failed
 *   LATE_PAID settles at 8s, no webhook ever    -> job fails it, sweep finds the charge, refund
 *   LATE_HOOK settles at 8s, webhook at 9s      -> job fails it, webhook contradicts, refund
 * </pre>
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    private record Payment(String ref, Instant settleAt, PaymentStatus settledAs, boolean refunded) {
        Payment(String ref, Instant settleAt, PaymentStatus settledAs) { this(ref, settleAt, settledAs, false); }
        static Payment stuck(String ref) { return new Payment(ref, null, PaymentStatus.PENDING); }
    }

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-provider");
        t.setDaemon(true);
        return t;
    });

    private final RestClient http = RestClient.create();
    private final CheckoutProperties props;

    public FakePaymentGateway(CheckoutProperties props) {
        this.props = props;
    }

    @Override
    public InitiateResult initiate(String orderId, BigDecimal amount, String currency, Map<String, String> metadata) {
        String scenario = metadata.getOrDefault("scenario", props.getGateway().getDefaultScenario())
                .toUpperCase(Locale.ROOT);

        // Idempotent on the order id: a retried initiate returns the original
        // payment and does not schedule a second webhook or a second charge.
        Payment existing = payments.get(orderId);
        if (existing != null) {
            log.info("[provider] initiate order={} is a replay -> ref={}", orderId, existing.ref());
            return InitiateResult.accepted(existing.ref());
        }

        String ref = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        Instant now = Instant.now();
        log.info("[provider] initiate order={} amount={} {} scenario={}", orderId, amount, currency, scenario);

        return switch (scenario) {
            case "REJECT" -> {
                payments.put(orderId, new Payment(ref, now, PaymentStatus.FAILED));
                yield InitiateResult.rejected(ref);
            }
            case "STUCK" -> {
                payments.put(orderId, Payment.stuck(ref));
                yield InitiateResult.accepted(ref);
            }
            case "SLOW" -> {
                settleIn(orderId, ref, Duration.ofSeconds(1), PaymentStatus.PAID);
                sendWebhookIn(orderId, ref, Duration.ofSeconds(10), "PAID");
                yield InitiateResult.accepted(ref);
            }
            case "NEVER" -> {
                settleIn(orderId, ref, Duration.ofSeconds(1), PaymentStatus.PAID);
                yield InitiateResult.accepted(ref);
            }
            case "DECLINED" -> {
                settleIn(orderId, ref, Duration.ofMillis(300), PaymentStatus.FAILED);
                sendWebhookIn(orderId, ref, Duration.ofMillis(400), "FAILED");
                yield InitiateResult.accepted(ref);
            }
            case "LATE_PAID" -> {
                // Settles well after our window closes, and never says a word.
                settleIn(orderId, ref, Duration.ofSeconds(8), PaymentStatus.PAID);
                yield InitiateResult.accepted(ref);
            }
            case "LATE_HOOK" -> {
                // Same, but eventually tells us -- after we have already failed it.
                settleIn(orderId, ref, Duration.ofSeconds(8), PaymentStatus.PAID);
                sendWebhookIn(orderId, ref, Duration.ofSeconds(9), "PAID");
                yield InitiateResult.accepted(ref);
            }
            case "UNKNOWN" -> {
                // The call "times out" -- but the payment is very much alive.
                settleIn(orderId, ref, Duration.ofSeconds(1), PaymentStatus.PAID);
                yield InitiateResult.unknown();
            }
            default -> {
                settleIn(orderId, ref, Duration.ofMillis(300), PaymentStatus.PAID);
                sendWebhookIn(orderId, ref, Duration.ofMillis(400), "PAID");
                yield InitiateResult.accepted(ref);
            }
        };
    }

    @Override
    public PaymentStatus getStatus(String orderId) {
        Payment p = payments.get(orderId);
        if (p == null) {
            return PaymentStatus.NOT_FOUND;
        }
        if (p.settleAt() == null || Instant.now().isBefore(p.settleAt())) {
            return PaymentStatus.PENDING;
        }
        return p.settledAs();
    }

    @Override
    public RefundResult refund(String orderId) {
        Payment p = payments.get(orderId);
        if (p == null || p.settleAt() == null || Instant.now().isBefore(p.settleAt())
                || p.settledAs() != PaymentStatus.PAID) {
            log.info("[provider] refund order={} -- nothing to reverse", orderId);
            return RefundResult.nothingToRefund();
        }
        if (p.refunded()) {
            log.info("[provider] refund order={} is a replay -> already reversed", orderId);
            return RefundResult.refunded("rfnd_" + p.ref().substring(4));
        }
        payments.put(orderId, new Payment(p.ref(), p.settleAt(), p.settledAs(), true));
        String refundRef = "rfnd_" + p.ref().substring(4);
        log.info("[provider] refund order={} reversed ref={}", orderId, refundRef);
        return RefundResult.refunded(refundRef);
    }

    private void settleIn(String orderId, String ref, Duration delay, PaymentStatus outcome) {
        payments.put(orderId, new Payment(ref, Instant.now().plus(delay), outcome));
    }

    private void sendWebhookIn(String orderId, String ref, Duration delay, String status) {
        clock.schedule(() -> {
            String url = props.getGateway().getWebhookUrl();
            log.info("[provider] delivering webhook order={} status={} -> {}", orderId, status, url);
            try {
                http.post().uri(url)
                        .header("Content-Type", "application/json")
                        .body(Map.of(
                                "eventId", "evt_" + UUID.randomUUID().toString().substring(0, 12),
                                "orderId", orderId,
                                "status", status,
                                "paymentRef", ref))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("[provider] webhook delivery for order={} failed: {}", orderId, e.toString());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        clock.shutdownNow();
    }
}
