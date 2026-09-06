package com.example.checkout.reconcile;

import com.example.checkout.config.CheckoutProperties;
import com.example.checkout.domain.Order;
import com.example.checkout.domain.RefundState;
import com.example.checkout.payment.PaymentGateway;
import com.example.checkout.payment.PaymentGateway.PaymentStatus;
import com.example.checkout.payment.PaymentGateway.RefundResult;
import com.example.checkout.store.OrderRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up after the reconciliation job's one wrong answer.
 * <p>
 * When the job fails an order because the provider would not confirm payment in
 * time, that verdict may be premature -- the payment can settle a moment later.
 * This runs in two phases:
 *
 * <ol>
 *   <li><b>Verify.</b> After we fail an order, ask the provider again on a
 *       decaying schedule. A single check would only catch a payment that
 *       settled at that one moment, so the checks tail off over hours before we
 *       accept the failure. Paid at any point: we charged a customer for an
 *       order we told them had failed.</li>
 *   <li><b>Refund.</b> Reverse those charges.</li>
 * </ol>
 *
 * A late webhook can reach the same conclusion sooner, in which case it flags the
 * order and phase one never sees it. Both paths converge on the same guarded
 * update, so an order is never queued for two refunds.
 */
@Component
public class RefundJob {

    private static final Logger log = LoggerFactory.getLogger(RefundJob.class);

    private final OrderRepository orders;
    private final PaymentGateway gateway;
    private final CheckoutProperties props;

    public RefundJob(OrderRepository orders, PaymentGateway gateway, CheckoutProperties props) {
        this.orders = orders;
        this.gateway = gateway;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${checkout.refund.interval}")
    public void run() {
        verifyFailedOrders();
        executeRefunds();
    }

    /** Phase one: was the job right to fail these? */
    public void verifyFailedOrders() {
        List<String> due = orders.leaseForVerification(props.getRefund().getBatchSize(), props.getRefund().getLease());
        for (String orderId : due) {
            try {
                verify(orderId);
            } catch (RuntimeException e) {
                log.warn("verify order={} failed: {} -- will retry after lease", orderId, e.toString());
            }
        }
    }

    private void verify(String orderId) {
        Order order = orders.find(orderId).orElse(null);
        if (order == null) {
            return;
        }

        if (gateway.getStatus(orderId) == PaymentStatus.PAID) {
            if (orders.requireRefund(orderId, null)) {
                log.warn("verify order={} was failed but the provider took the money -- refund required", orderId);
            }
            return;
        }

        // Not paid -- but "not paid yet" and "never going to be paid" look
        // identical from here, so we ask again later rather than deciding now.
        List<Duration> schedule = props.getRefund().getVerifySchedule();
        int nextAttempt = order.verifyAttempts() + 1;
        if (nextAttempt < schedule.size()) {
            Duration next = schedule.get(nextAttempt);
            orders.rescheduleVerification(orderId, next);
            log.info("verify order={} still unpaid, check {} of {} in {}",
                    orderId, nextAttempt + 1, schedule.size(), next);
        } else {
            orders.clearVerification(orderId);
            log.info("verify order={} unpaid after {} checks -- the failure stands", orderId, schedule.size());
        }
    }

    /** Phase two: put the money back. */
    public void executeRefunds() {
        List<String> claimed = orders.claimRefunds(props.getRefund().getBatchSize(), props.getRefund().getLease());
        for (String orderId : claimed) {
            try {
                RefundResult result = gateway.refund(orderId);
                switch (result.outcome()) {
                    case REFUNDED -> {
                        orders.settleRefund(orderId, RefundState.REFUNDED, result.refundRef());
                        log.info("refund order={} reversed ref={}", orderId, result.refundRef());
                    }
                    case NOTHING_TO_REFUND -> {
                        orders.settleRefund(orderId, RefundState.UNREFUNDABLE, null);
                        log.info("refund order={} had nothing to reverse", orderId);
                    }
                    case UNKNOWN -> {
                        // We still owe the money. Back on the queue -- refund is
                        // idempotent on the order id, so retrying cannot double-refund.
                        orders.releaseRefund(orderId);
                        log.warn("refund order={} outcome unknown -- requeued", orderId);
                    }
                }
            } catch (RuntimeException e) {
                orders.releaseRefund(orderId);
                log.warn("refund order={} threw {} -- requeued", orderId, e.toString());
            }
        }
    }
}
