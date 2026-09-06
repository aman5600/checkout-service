package com.example.checkout.service;

import com.example.checkout.config.CheckoutProperties;
import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.payment.PaymentGateway;
import com.example.checkout.payment.PaymentGateway.InitiateResult;
import com.example.checkout.store.OrderRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final OrderRepository orders;
    private final PaymentGateway gateway;
    private final CheckoutProperties props;

    public CheckoutService(OrderRepository orders, PaymentGateway gateway, CheckoutProperties props) {
        this.orders = orders;
        this.gateway = gateway;
        this.props = props;
    }

    /**
     * Order goes in, payment starts, caller gets an id back. Nothing waits.
     * <p>
     * The row -- and therefore the safety net -- is committed before the
     * provider is called, so an order is covered even if that call never
     * returns.
     */
    public Order place(String orderId, BigDecimal amount, String currency, String scenario) {
        boolean fresh = orders.insertIfAbsent(orderId, amount, currency, props.getReconcileWindow());

        if (!fresh) {
            Order existing = load(orderId);
            // A repeat of a checkout that died before it reached the provider
            // still needs its payment started. Anything else is already handled.
            if (existing.status() == OrderStatus.PENDING && existing.paymentRef() == null) {
                log.info("checkout replay order={} resuming initiate", orderId);
                return initiate(orderId, amount, currency, scenario);
            }
            log.info("checkout replay order={} status={} returning existing", orderId, existing.status());
            return existing;
        }

        return initiate(orderId, amount, currency, scenario);
    }

    private Order initiate(String orderId, BigDecimal amount, String currency, String scenario) {
        Map<String, String> metadata = scenario == null ? Map.of() : Map.of("scenario", scenario);

        InitiateResult result;
        try {
            result = gateway.initiate(orderId, amount, currency, metadata);
        } catch (RuntimeException e) {
            // The call blew up, so we do not know whether money moved. Leave the
            // order PENDING with its timer armed and let reconciliation find out.
            log.warn("initiate order={} failed with {} -- treating as UNKNOWN", orderId, e.toString());
            result = InitiateResult.unknown();
        }

        switch (result.outcome()) {
            case ACCEPTED -> {
                orders.attachPaymentRef(orderId, result.paymentRef());
                log.info("initiate order={} accepted ref={}", orderId, result.paymentRef());
            }
            case REJECTED -> {
                orders.resolve(orderId, OrderStatus.FAILED, result.paymentRef(), "INITIATE");
                log.info("initiate order={} rejected", orderId);
            }
            case UNKNOWN -> log.warn("initiate order={} unknown -- reconciliation will decide", orderId);
        }

        return load(orderId);
    }

    /**
     * Applies a provider webhook. Returns true if this webhook is the thing that
     * actually resolved the order; false means it lost the race or is a duplicate,
     * and either way the caller answers 200.
     */
    public boolean applyWebhook(String orderId, OrderStatus terminal, String paymentRef) {
        if (!terminal.isTerminal()) {
            log.info("webhook order={} status={} is not terminal -- ignored", orderId, terminal);
            return false;
        }

        boolean won = orders.resolve(orderId, terminal, paymentRef, "WEBHOOK");
        if (won) {
            log.info("webhook order={} resolved as {}", orderId, terminal);
            return true;
        }

        // We lost the race, or this is a duplicate. Usually that means the webhook
        // is telling us something we already know -- but not always. A PAID event
        // for an order we failed is the provider correcting us, and it is the
        // cheapest possible signal that we owe the customer money back.
        orders.find(orderId).ifPresent(order -> {
            if (terminal == OrderStatus.PAID && order.status() == OrderStatus.FAILED) {
                if (orders.requireRefund(orderId, paymentRef)) {
                    log.warn("webhook order={} says PAID but we failed it -- refund required", orderId);
                }
            }
        });

        log.info("webhook order={} ignored -- already resolved or unknown", orderId);
        return false;
    }

    public Optional<Order> get(String orderId) {
        return orders.find(orderId);
    }

    private Order load(String orderId) {
        return orders.find(orderId)
                .orElseThrow(() -> new NoSuchElementException("order " + orderId + " vanished"));
    }
}
