package com.example.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.payment.PaymentGateway.InitiateResult;
import com.example.checkout.payment.PaymentGateway.PaymentStatus;
import com.example.checkout.reconcile.ReconciliationJob;
import com.example.checkout.service.CheckoutService;
import com.example.checkout.store.OrderRepository;
import com.example.checkout.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The race the whole design exists to manage: webhook versus deadline. */
class CheckoutFlowTest extends IntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired ReconciliationJob reconciliation;
    @Autowired OrderRepository orders;

    private static final BigDecimal TENNER = new BigDecimal("10.00");

    private Order place(String orderId) {
        return checkout.place(orderId, TENNER, "GBP", null);
    }

    private Order load(String orderId) {
        return orders.find(orderId).orElseThrow();
    }

    @Test
    @DisplayName("checkout returns immediately with the order pending and its timer armed")
    void checkoutDoesNotWait() {
        Order order = place("o1");

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.reconcileDueAt()).isNotNull();
        assertThat(order.paymentRef()).isEqualTo("pay_o1");
    }

    @Test
    @DisplayName("a retried checkout returns the same order and does not charge again")
    void checkoutIsIdempotent() {
        place("o1");
        Order second = place("o1");

        assertThat(second.orderId()).isEqualTo("o1");
        assertThat(gateway.initiateCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a checkout that died before reaching the provider resumes on retry")
    void checkoutResumesAnUnstartedPayment() {
        // First attempt got the row in but never recorded a payment reference.
        orders.insertIfAbsent("o1", TENNER, "GBP", java.time.Duration.ZERO);
        assertThat(load("o1").paymentRef()).isNull();

        place("o1");

        assertThat(gateway.initiateCalls()).isEqualTo(1);
        assertThat(load("o1").paymentRef()).isEqualTo("pay_o1");
    }

    @Test
    @DisplayName("a rejected payment fails the order without waiting for anything")
    void rejectedPaymentFailsImmediately() {
        gateway.whenInitiate("o1", InitiateResult.rejected("pay_o1"));

        Order order = place("o1");

        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.resolvedBy()).isEqualTo("INITIATE");
        assertThat(order.reconcileDueAt()).isNull();
    }

    @Test
    @DisplayName("an initiate call that times out leaves the order pending, never failed")
    void unknownInitiateIsNotAFailure() {
        gateway.whenInitiate("o1", InitiateResult.unknown());

        Order order = place("o1");

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.reconcileDueAt()).isNotNull();
    }

    @Test
    @DisplayName("the webhook resolves the order and takes it off the job's list")
    void webhookWinsTheRace() {
        place("o1");

        assertThat(checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1")).isTrue();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.resolvedBy()).isEqualTo("WEBHOOK");

        reconciliation.reconcileDueOrders();
        assertThat(load("o1").resolvedBy()).isEqualTo("WEBHOOK");
    }

    @Test
    @DisplayName("a duplicate webhook is absorbed silently")
    void duplicateWebhookIsANoOp() {
        place("o1");

        assertThat(checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1")).isTrue();
        assertThat(checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1")).isFalse();

        assertThat(load("o1").status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("a webhook for an unknown order is absorbed rather than exploding")
    void webhookForUnknownOrder() {
        assertThat(checkout.applyWebhook("ghost", OrderStatus.PAID, "pay_ghost")).isFalse();
    }

    @Test
    @DisplayName("a non-terminal webhook is ignored")
    void pendingWebhookIsIgnored() {
        place("o1");

        assertThat(checkout.applyWebhook("o1", OrderStatus.PENDING, "pay_o1")).isFalse();
        assertThat(load("o1").status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("with no webhook, the deadline job resolves the order from the provider")
    void jobResolvesWhenNoWebhookArrives() {
        place("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);

        reconciliation.reconcileDueOrders();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.resolvedBy()).isEqualTo("JOB");
        assertThat(order.reconcileDueAt()).isNull();
    }

    @Test
    @DisplayName("a payment the provider will not confirm in time is failed, and re-checked later")
    void jobFailsAnUnconfirmedPayment() {
        place("o1");
        gateway.whenStatus("o1", PaymentStatus.PENDING);

        reconciliation.reconcileDueOrders();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.resolvedBy()).isEqualTo("JOB");
        assertThat(order.verifyDueAt()).isNotNull();
    }

    @Test
    @DisplayName("a payment the provider never heard of is failed")
    void jobFailsUnknownPayment() {
        place("o1");
        gateway.whenStatus("o1", PaymentStatus.NOT_FOUND);

        reconciliation.reconcileDueOrders();

        assertThat(load("o1").status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("running the job twice over the same order resolves it once")
    void jobIsSafeToRunTwice() {
        place("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);

        reconciliation.reconcileDueOrders();
        reconciliation.reconcileDueOrders();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.resolvedBy()).isEqualTo("JOB");
    }

    @Test
    @DisplayName("a status call that blows up leaves the order pending for a later pass")
    void jobSurvivesAProviderOutage() {
        place("o1");
        gateway.whenStatusThrows("o1");

        reconciliation.reconcileDueOrders();

        assertThat(load("o1").status()).isEqualTo(OrderStatus.PENDING);
    }
}
