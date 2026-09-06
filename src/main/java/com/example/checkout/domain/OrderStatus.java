package com.example.checkout.domain;

public enum OrderStatus {
    PENDING,
    PAID,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
