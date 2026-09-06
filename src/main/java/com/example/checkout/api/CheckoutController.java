package com.example.checkout.api;

import com.example.checkout.domain.Order;
import com.example.checkout.service.CheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CheckoutController {

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /** The order id is client-supplied: it is the correlation reference and the idempotency key. */
    public record CheckoutRequest(
            @NotBlank String orderId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String currency,
            String scenario) {
    }

    public record OrderView(
            String orderId,
            String status,
            BigDecimal amount,
            String currency,
            String paymentRef,
            Instant reconcileDueAt,
            String resolvedBy,
            String refundState,
            String refundRef) {

        static OrderView of(Order o) {
            return new OrderView(o.orderId(), o.status().name(), o.amount(), o.currency(),
                    o.paymentRef(), o.reconcileDueAt(), o.resolvedBy(),
                    o.refundState() == null ? null : o.refundState().name(), o.refundRef());
        }
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderView> place(@Valid @RequestBody CheckoutRequest request) {
        Order order = checkout.place(request.orderId(), request.amount(), request.currency(), request.scenario());
        return ResponseEntity.accepted().body(OrderView.of(order));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderView> get(@PathVariable String orderId) {
        return checkout.get(orderId)
                .map(o -> ResponseEntity.ok(OrderView.of(o)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
