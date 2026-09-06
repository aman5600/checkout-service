package com.example.checkout.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The provider port. Two calls: start a payment, and ask what happened to one.
 * <p>
 * {@code orderId} doubles as the provider's idempotency key, which is what makes
 * retrying {@link #initiate} after an UNKNOWN outcome safe.
 */
public interface PaymentGateway {

    InitiateResult initiate(String orderId, BigDecimal amount, String currency, Map<String, String> metadata);

    PaymentStatus getStatus(String orderId);

    /**
     * Puts money back. Called only when we have already failed an order that the
     * provider then confirmed as paid.
     * <p>
     * Idempotent on the order id, so a retry after an UNKNOWN outcome does not
     * refund twice.
     */
    RefundResult refund(String orderId);

    /**
     * Three outcomes, not two. A timeout or connection reset is UNKNOWN, never
     * REJECTED -- the money may well have moved.
     */
    enum Outcome { ACCEPTED, REJECTED, UNKNOWN }

    enum PaymentStatus { PAID, FAILED, PENDING, NOT_FOUND }

    enum RefundOutcome {
        /** Money is on its way back. */
        REFUNDED,
        /** There was nothing to reverse -- no charge ever landed. */
        NOTHING_TO_REFUND,
        /** The call failed. We still owe the money; try again. */
        UNKNOWN
    }

    record RefundResult(RefundOutcome outcome, String refundRef) {
        public static RefundResult refunded(String ref) { return new RefundResult(RefundOutcome.REFUNDED, ref); }
        public static RefundResult nothingToRefund() { return new RefundResult(RefundOutcome.NOTHING_TO_REFUND, null); }
        public static RefundResult unknown() { return new RefundResult(RefundOutcome.UNKNOWN, null); }
    }

    record InitiateResult(Outcome outcome, String paymentRef) {
        public static InitiateResult accepted(String ref) { return new InitiateResult(Outcome.ACCEPTED, ref); }
        public static InitiateResult rejected(String ref) { return new InitiateResult(Outcome.REJECTED, ref); }
        public static InitiateResult unknown() { return new InitiateResult(Outcome.UNKNOWN, null); }
    }
}
