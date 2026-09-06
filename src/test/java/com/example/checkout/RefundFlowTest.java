package com.example.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.domain.RefundState;
import com.example.checkout.payment.PaymentGateway.PaymentStatus;
import com.example.checkout.payment.PaymentGateway.RefundResult;
import com.example.checkout.reconcile.ReconciliationJob;
import com.example.checkout.reconcile.RefundJob;
import com.example.checkout.service.CheckoutService;
import com.example.checkout.store.OrderRepository;
import com.example.checkout.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The edge case the deadline creates: we fail an order because the provider will
 * not confirm it in five seconds, and the payment settles anyway. The customer
 * has been charged for an order we told them failed, so the money has to go back.
 */
class RefundFlowTest extends IntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired ReconciliationJob reconciliation;
    @Autowired RefundJob refunds;
    @Autowired OrderRepository orders;

    private static final BigDecimal TENNER = new BigDecimal("10.00");

    private Order load(String orderId) {
        return orders.find(orderId).orElseThrow();
    }

    /** Places an order and lets the deadline job fail it, as if payment were slow. */
    private void placeAndFailOnDeadline(String orderId) {
        checkout.place(orderId, TENNER, "GBP", null);
        gateway.whenStatus(orderId, PaymentStatus.PENDING);
        reconciliation.reconcileDueOrders();
        assertThat(load(orderId).status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("a payment that settles after we failed the order is found and refunded")
    void quietLateSettlementIsRefunded() {
        placeAndFailOnDeadline("o1");

        // The payment settles moments later. Nothing tells us; we go and look.
        gateway.whenStatus("o1", PaymentStatus.PAID);
        refunds.verifyFailedOrders();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REQUIRED);

        refunds.executeRefunds();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.refundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.refundRef()).isEqualTo("rfnd_o1");
        assertThat(gateway.refundCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a late webhook contradicting our failure triggers the refund")
    void lateWebhookTriggersRefund() {
        placeAndFailOnDeadline("o1");

        // The provider finally speaks up, and disagrees with us.
        assertThat(checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1")).isFalse();

        Order flagged = load("o1");
        assertThat(flagged.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(flagged.refundState()).isEqualTo(RefundState.REQUIRED);
        assertThat(flagged.verifyDueAt()).isNull();

        refunds.executeRefunds();
        assertThat(load("o1").refundState()).isEqualTo(RefundState.REFUNDED);
    }

    @Test
    @DisplayName("one unpaid answer is not enough to close the question")
    void oneUnpaidCheckIsNotConclusive() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.FAILED);

        refunds.verifyFailedOrders();

        Order order = load("o1");
        assertThat(order.verifyAttempts()).isEqualTo(1);
        assertThat(order.verifyDueAt()).isNotNull();
    }

    @Test
    @DisplayName("a genuine failure is accepted once the schedule runs out")
    void genuineFailureStopsBeingChecked() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.FAILED);

        // Three checks configured for tests; the third exhausts the schedule.
        refunds.verifyFailedOrders();
        refunds.verifyFailedOrders();
        refunds.verifyFailedOrders();

        Order order = load("o1");
        assertThat(order.refundState()).isNull();
        assertThat(order.verifyDueAt()).isNull();
        assertThat(orders.leaseForVerification(10, java.time.Duration.ofSeconds(30))).isEmpty();

        refunds.executeRefunds();
        assertThat(gateway.refundCalls()).isZero();
    }

    @Test
    @DisplayName("a payment that settles long after the first check is still caught")
    void lateSettlementIsCaughtByTheTail() {
        placeAndFailOnDeadline("o1");

        // Unpaid at the first two checks -- a single check would have given up here.
        gateway.whenStatus("o1", PaymentStatus.PENDING);
        refunds.verifyFailedOrders();
        refunds.verifyFailedOrders();
        assertThat(load("o1").refundState()).isNull();

        // Settles before the last one.
        gateway.whenStatus("o1", PaymentStatus.PAID);
        refunds.verifyFailedOrders();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REQUIRED);

        refunds.executeRefunds();
        assertThat(load("o1").refundState()).isEqualTo(RefundState.REFUNDED);
    }

    @Test
    @DisplayName("a refund abandoned mid-flight is picked up again, not stranded")
    void orphanedRefundIsRecovered() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);
        refunds.verifyFailedOrders();

        // Claimed, then the worker dies before calling the provider.
        orders.claimRefunds(10, java.time.Duration.ofSeconds(30));
        assertThat(load("o1").refundState()).isEqualTo(RefundState.IN_PROGRESS);
        jdbc.update("UPDATE orders SET refund_due_at = now() - INTERVAL '1 minute' WHERE order_id = 'o1'");

        refunds.executeRefunds();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(gateway.refundCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a webhook and the sweep finding the same overcharge refund it once")
    void bothDiscoveryPathsRefundOnlyOnce() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);

        checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1");
        refunds.verifyFailedOrders();
        refunds.executeRefunds();
        refunds.executeRefunds();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(gateway.refundCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a refund whose outcome is unknown is retried, not written off")
    void unknownRefundIsRetried() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);
        refunds.verifyFailedOrders();

        gateway.whenRefund("o1", RefundResult.unknown());
        refunds.executeRefunds();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REQUIRED);

        gateway.whenRefund("o1", RefundResult.refunded("rfnd_o1"));
        refunds.executeRefunds();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(gateway.refundCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("a refund the provider says is unnecessary is closed, not retried forever")
    void nothingToRefundIsClosedOut() {
        placeAndFailOnDeadline("o1");
        gateway.whenStatus("o1", PaymentStatus.PAID);
        refunds.verifyFailedOrders();

        gateway.whenRefund("o1", RefundResult.nothingToRefund());
        refunds.executeRefunds();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.UNREFUNDABLE);

        refunds.executeRefunds();
        assertThat(gateway.refundCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("an order paid on time is never dragged into the refund path")
    void paidOrdersAreLeftAlone() {
        checkout.place("o1", TENNER, "GBP", null);
        checkout.applyWebhook("o1", OrderStatus.PAID, "pay_o1");

        refunds.verifyFailedOrders();
        refunds.executeRefunds();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.refundState()).isNull();
        assertThat(gateway.refundCalls()).isZero();
    }

    @Test
    @DisplayName("a provider outage during verification leaves the order to be re-checked")
    void verificationSurvivesAnOutage() {
        placeAndFailOnDeadline("o1");

        gateway.whenStatusThrows("o1");
        refunds.verifyFailedOrders();

        Order order = load("o1");
        assertThat(order.refundState()).isNull();
        assertThat(order.verifyDueAt()).isNotNull();
    }
}
