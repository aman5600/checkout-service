package com.example.checkout.domain;

/**
 * Tracks money owed back on an order we failed but the provider actually took.
 * Null means the question never arose.
 */
public enum RefundState {
    /** We know the payment settled after we gave up. Money must go back. */
    REQUIRED,
    /** A worker has claimed it and the refund call is in flight. */
    IN_PROGRESS,
    /** Provider confirmed the reversal. */
    REFUNDED,
    /** Provider says there is nothing to reverse. Nobody is out of pocket. */
    UNREFUNDABLE
}
