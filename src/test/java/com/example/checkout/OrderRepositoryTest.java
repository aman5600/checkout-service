package com.example.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.domain.RefundState;
import com.example.checkout.store.OrderRepository;
import com.example.checkout.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The storage layer carries the concurrency guarantees, so it gets tested directly. */
class OrderRepositoryTest extends IntegrationTest {

    @Autowired
    OrderRepository orders;

    private static final BigDecimal TENNER = new BigDecimal("10.00");

    private boolean insert(String id) {
        return orders.insertIfAbsent(id, TENNER, "GBP", Duration.ZERO);
    }

    private Order load(String id) {
        return orders.find(id).orElseThrow();
    }

    @Test
    @DisplayName("a repeated order id is absorbed rather than inserted twice")
    void insertIsIdempotent() {
        assertThat(insert("o1")).isTrue();
        assertThat(insert("o1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("an order is armed with a timer the moment it exists")
    void insertArmsTheTimer() {
        orders.insertIfAbsent("o1", TENNER, "GBP", Duration.ofSeconds(5));
        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.reconcileDueAt()).isNotNull();
    }

    @Test
    @DisplayName("only the first caller resolves an order; everyone after is told they lost")
    void resolveIsGuarded() {
        insert("o1");

        assertThat(orders.resolve("o1", OrderStatus.PAID, "pay_1", "WEBHOOK")).isTrue();
        assertThat(orders.resolve("o1", OrderStatus.FAILED, null, "JOB")).isFalse();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.resolvedBy()).isEqualTo("WEBHOOK");
    }

    @Test
    @DisplayName("resolving disarms the timer, so the job stops seeing the row")
    void resolveClearsTheTimer() {
        insert("o1");
        orders.resolve("o1", OrderStatus.PAID, "pay_1", "WEBHOOK");

        assertThat(load("o1").reconcileDueAt()).isNull();
        assertThat(orders.leaseDue(10, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("resolving an order that does not exist changes nothing")
    void resolveUnknownOrder() {
        assertThat(orders.resolve("ghost", OrderStatus.PAID, null, "WEBHOOK")).isFalse();
    }

    @Test
    @DisplayName("the database refuses to hold a terminal order with a live timer")
    void terminalOrdersCannotKeepATimer() {
        insert("o1");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET status = 'PAID' WHERE order_id = 'o1'"))
                .hasMessageContaining("orders_timer_chk");
    }

    @Test
    @DisplayName("only orders past their deadline are claimed")
    void leaseSkipsOrdersNotYetDue() {
        orders.insertIfAbsent("due", TENNER, "GBP", Duration.ZERO);
        orders.insertIfAbsent("later", TENNER, "GBP", Duration.ofMinutes(5));

        assertThat(orders.leaseDue(10, Duration.ofSeconds(30))).containsExactly("due");
    }

    @Test
    @DisplayName("a claimed order is leased, so the next pass leaves it alone")
    void leasePushesTheTimerOut() {
        insert("o1");

        assertThat(orders.leaseDue(10, Duration.ofSeconds(30))).containsExactly("o1");
        assertThat(orders.leaseDue(10, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("the batch size caps how much one pass claims")
    void leaseRespectsBatchSize() {
        insert("o1");
        insert("o2");
        insert("o3");

        List<String> claimed = orders.leaseDue(2, Duration.ofSeconds(30));
        assertThat(claimed).hasSize(2);
    }

    @Test
    @DisplayName("failing an order by job schedules a second look at it")
    void failByJobArmsVerification() {
        insert("o1");

        assertThat(orders.failByJob("o1", Duration.ZERO)).isTrue();

        Order order = load("o1");
        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.resolvedBy()).isEqualTo("JOB");
        assertThat(order.reconcileDueAt()).isNull();
        assertThat(order.verifyDueAt()).isNotNull();
        assertThat(orders.leaseForVerification(10, Duration.ofSeconds(30))).containsExactly("o1");
    }

    @Test
    @DisplayName("an order paid on time is never queued for verification")
    void paidOrdersAreNotVerified() {
        insert("o1");
        orders.resolve("o1", OrderStatus.PAID, "pay_1", "WEBHOOK");

        assertThat(orders.leaseForVerification(10, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("two discoveries of the same overcharge queue one refund, not two")
    void refundIsFlaggedOnlyOnce() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);

        assertThat(orders.requireRefund("o1", "pay_1")).isTrue();
        assertThat(orders.requireRefund("o1", "pay_1")).isFalse();

        assertThat(load("o1").refundState()).isEqualTo(RefundState.REQUIRED);
        assertThat(orders.claimRefunds(10, Duration.ofSeconds(30))).containsExactly("o1");
    }

    @Test
    @DisplayName("a refund cannot be queued against an order that was paid")
    void paidOrdersAreNeverRefundedHere() {
        insert("o1");
        orders.resolve("o1", OrderStatus.PAID, "pay_1", "WEBHOOK");

        assertThat(orders.requireRefund("o1", "pay_1")).isFalse();
    }

    @Test
    @DisplayName("a claimed refund is not handed to a second worker")
    void claimingARefundTakesItOffTheQueue() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);
        orders.requireRefund("o1", "pay_1");

        assertThat(orders.claimRefunds(10, Duration.ofSeconds(30))).containsExactly("o1");
        assertThat(orders.claimRefunds(10, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("a refund whose worker died is reclaimed once its lease expires")
    void orphanedRefundsAreReclaimed() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);
        orders.requireRefund("o1", "pay_1");

        // Claimed with no lease at all, then nothing else happens -- the worker died.
        assertThat(orders.claimRefunds(10, Duration.ZERO)).containsExactly("o1");
        assertThat(load("o1").refundState()).isEqualTo(RefundState.IN_PROGRESS);

        // Without a lease on the claim this row would be stranded forever.
        assertThat(orders.claimRefunds(10, Duration.ofSeconds(30))).containsExactly("o1");
    }

    @Test
    @DisplayName("a refund that completed is never reclaimed")
    void settledRefundsAreNotReclaimed() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);
        orders.requireRefund("o1", "pay_1");
        orders.claimRefunds(10, Duration.ZERO);

        orders.settleRefund("o1", RefundState.REFUNDED, "rfnd_1");

        assertThat(load("o1").refundDueAt()).isNull();
        assertThat(orders.claimRefunds(10, Duration.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("rescheduling a verification spends one attempt and sets the next")
    void reschedulingVerificationCountsTheAttempt() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);
        orders.leaseForVerification(10, Duration.ofSeconds(30));

        orders.rescheduleVerification("o1", Duration.ZERO);

        Order order = load("o1");
        assertThat(order.verifyAttempts()).isEqualTo(1);
        assertThat(order.verifyDueAt()).isNotNull();
        assertThat(orders.leaseForVerification(10, Duration.ofSeconds(30))).containsExactly("o1");
    }

    @Test
    @DisplayName("a failed refund attempt goes back on the queue")
    void releasedRefundsAreRetried() {
        insert("o1");
        orders.failByJob("o1", Duration.ZERO);
        orders.requireRefund("o1", "pay_1");
        orders.claimRefunds(10, Duration.ofSeconds(30));

        orders.releaseRefund("o1");
        assertThat(load("o1").refundDueAt()).isNull();

        assertThat(orders.claimRefunds(10, Duration.ofSeconds(30))).containsExactly("o1");
    }
}
