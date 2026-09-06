package com.example.checkout.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order row.
 * <p>
 * Two timers live here, and only one can be active at a time.
 * {@code reconcileDueAt} runs while the order is PENDING: "if nobody has
 * resolved this by then, go ask the provider". {@code verifyDueAt} runs after
 * the job has failed an order: "go back and check we were right about that".
 */
public record Order(
        String orderId,
        OrderStatus status,
        BigDecimal amount,
        String currency,
        String paymentRef,
        Instant reconcileDueAt,
        Instant verifyDueAt,
        int verifyAttempts,
        RefundState refundState,
        String refundRef,
        Instant refundDueAt,
        String resolvedBy,
        Instant createdAt,
        Instant updatedAt) {
}
