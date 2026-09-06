package com.example.checkout.reconcile;

import com.example.checkout.config.CheckoutProperties;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.payment.PaymentGateway;
import com.example.checkout.payment.PaymentGateway.PaymentStatus;
import com.example.checkout.store.OrderRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fallback. Every tick it takes the orders whose window has expired and
 * asks the provider what actually happened.
 * <p>
 * It only ever reads from the provider, which is what makes at-least-once
 * execution safe here.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final OrderRepository orders;
    private final PaymentGateway gateway;
    private final CheckoutProperties props;

    public ReconciliationJob(OrderRepository orders, PaymentGateway gateway, CheckoutProperties props) {
        this.orders = orders;
        this.gateway = gateway;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${checkout.job.interval}")
    public void reconcileDueOrders() {
        List<String> due = orders.leaseDue(props.getJob().getBatchSize(), props.getJob().getLease());
        if (due.isEmpty()) {
            return;
        }

        log.info("reconcile pass claimed {} order(s)", due.size());
        for (String orderId : due) {
            try {
                reconcile(orderId);
            } catch (RuntimeException e) {
                // Leave it leased. When the lease expires a later pass retries,
                // which is free because getStatus does not change anything.
                log.warn("reconcile order={} failed: {} -- will retry after lease", orderId, e.toString());
            }
        }
    }

    private void reconcile(String orderId) {
        PaymentStatus status = gateway.getStatus(orderId);

        // One shot: anything the provider will not confirm as paid becomes FAILED.
        // That verdict can be wrong -- a payment still PENDING here may settle a
        // second later -- so failing an order also arms a verification timer, and
        // RefundJob goes back to check our answer.
        if (status == PaymentStatus.PAID) {
            boolean won = orders.resolve(orderId, OrderStatus.PAID, null, "JOB");
            log.info("reconcile order={} provider=PAID {}", orderId,
                    won ? "resolved as PAID" : "already resolved by the webhook -- no-op");
            return;
        }

        Duration firstCheck = props.getRefund().getVerifySchedule().get(0);
        boolean won = orders.failByJob(orderId, firstCheck);
        if (won) {
            log.info("reconcile order={} provider={} failed, re-check in {}", orderId, status, firstCheck);
        } else {
            log.info("reconcile order={} already resolved by the webhook -- no-op", orderId);
        }
    }
}
