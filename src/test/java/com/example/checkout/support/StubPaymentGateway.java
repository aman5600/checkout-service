package com.example.checkout.support;

import com.example.checkout.payment.PaymentGateway;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A payment provider that does exactly what the test tells it to, and counts calls. */
public class StubPaymentGateway implements PaymentGateway {

    private final Map<String, InitiateResult> initiateResults = new ConcurrentHashMap<>();
    private final Map<String, PaymentStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refundResults = new ConcurrentHashMap<>();
    private final Set<String> statusFailures = ConcurrentHashMap.newKeySet();

    private final AtomicInteger initiateCalls = new AtomicInteger();
    private final AtomicInteger refundCalls = new AtomicInteger();

    public void reset() {
        initiateResults.clear();
        statuses.clear();
        refundResults.clear();
        statusFailures.clear();
        initiateCalls.set(0);
        refundCalls.set(0);
    }

    public void whenInitiate(String orderId, InitiateResult result) {
        initiateResults.put(orderId, result);
    }

    public void whenStatus(String orderId, PaymentStatus status) {
        statuses.put(orderId, status);
    }

    /** Makes the provider unreachable for this order, the way an outage would. */
    public void whenStatusThrows(String orderId) {
        statusFailures.add(orderId);
    }

    public void whenRefund(String orderId, RefundResult result) {
        refundResults.put(orderId, result);
    }

    public int initiateCalls() {
        return initiateCalls.get();
    }

    public int refundCalls() {
        return refundCalls.get();
    }

    @Override
    public InitiateResult initiate(String orderId, BigDecimal amount, String currency, Map<String, String> metadata) {
        initiateCalls.incrementAndGet();
        return initiateResults.getOrDefault(orderId, InitiateResult.accepted("pay_" + orderId));
    }

    @Override
    public PaymentStatus getStatus(String orderId) {
        if (statusFailures.contains(orderId)) {
            throw new IllegalStateException("provider unreachable");
        }
        return statuses.getOrDefault(orderId, PaymentStatus.PENDING);
    }

    @Override
    public RefundResult refund(String orderId) {
        refundCalls.incrementAndGet();
        return refundResults.getOrDefault(orderId, RefundResult.refunded("rfnd_" + orderId));
    }
}
