package com.example.checkout.store;

import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderStatus;
import com.example.checkout.domain.RefundState;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Order> MAPPER = (rs, n) -> new Order(
            rs.getString("order_id"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("payment_ref"),
            instant(rs.getTimestamp("reconcile_due_at")),
            instant(rs.getTimestamp("verify_due_at")),
            rs.getInt("verify_attempts"),
            refundState(rs.getString("refund_state")),
            rs.getString("refund_ref"),
            instant(rs.getTimestamp("refund_due_at")),
            rs.getString("resolved_by"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static RefundState refundState(String value) {
        return value == null ? null : RefundState.valueOf(value);
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    // ---------------------------------------------------------------- orders

    /**
     * Insert the order PENDING with its timer already armed, in one statement.
     * Returns false if the id already exists, which is how a retried checkout
     * is absorbed instead of creating a second order.
     * <p>
     * The deadline comes from the database clock, not the application's, so
     * server skew cannot fire orders early or late.
     */
    public boolean insertIfAbsent(String orderId, BigDecimal amount, String currency, Duration window) {
        int rows = jdbc.update("""
                INSERT INTO orders (order_id, status, amount, currency, reconcile_due_at)
                VALUES (?, 'PENDING', ?, ?, now() + make_interval(secs => ?))
                ON CONFLICT (order_id) DO NOTHING
                """, orderId, amount, currency, seconds(window));
        return rows == 1;
    }

    public Optional<Order> find(String orderId) {
        return jdbc.query("SELECT * FROM orders WHERE order_id = ?", MAPPER, orderId).stream().findFirst();
    }

    /** Records the provider's reference without touching state. */
    public void attachPaymentRef(String orderId, String paymentRef) {
        jdbc.update("""
                UPDATE orders SET payment_ref = ?, updated_at = now()
                WHERE order_id = ? AND status = 'PENDING'
                """, paymentRef, orderId);
    }

    /**
     * The one guarded transition in the whole service.
     * <p>
     * Only a PENDING order can move, so whoever gets here first wins and every
     * later arrival -- a duplicate webhook, a late webhook, a re-run of the job
     * -- updates zero rows and is told so. This single statement settles the
     * webhook-versus-job race, dedupes webhooks, and makes the job re-runnable.
     *
     * @return true if this caller is the one that resolved the order
     */
    public boolean resolve(String orderId, OrderStatus terminal, String paymentRef, String resolvedBy) {
        int rows = jdbc.update("""
                UPDATE orders
                   SET status = ?,
                       payment_ref = COALESCE(?, payment_ref),
                       resolved_by = ?,
                       reconcile_due_at = NULL,
                       updated_at = now()
                 WHERE order_id = ? AND status = 'PENDING'
                """, terminal.name(), paymentRef, resolvedBy, orderId);
        return rows == 1;
    }

    /**
     * The job failing an order, which is the one verdict in this service that
     * might be wrong about money. Same guard as {@link #resolve}, but it also
     * arms the verification timer so we come back and check our own answer.
     */
    public boolean failByJob(String orderId, Duration verifyAfter) {
        int rows = jdbc.update("""
                UPDATE orders
                   SET status = 'FAILED',
                       resolved_by = 'JOB',
                       reconcile_due_at = NULL,
                       verify_due_at = now() + make_interval(secs => ?),
                       updated_at = now()
                 WHERE order_id = ? AND status = 'PENDING'
                """, seconds(verifyAfter), orderId);
        return rows == 1;
    }

    /**
     * Claim a batch of due orders and lease them, in one short transaction.
     * <p>
     * SKIP LOCKED keeps two workers off the same row; pushing the timer out by
     * the lease keeps the next pass off it while the status call is in flight.
     * The transaction closes before any network I/O -- we never hold row locks
     * across a call to the provider. If this process dies mid-call the lease
     * simply expires and a later pass retries, which is safe precisely because
     * the job only ever reads from the provider.
     */
    @Transactional
    public List<String> leaseDue(int batchSize, Duration lease) {
        List<String> ids = jdbc.queryForList("""
                SELECT order_id FROM orders
                 WHERE status = 'PENDING' AND reconcile_due_at <= now()
                 ORDER BY reconcile_due_at
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, String.class, batchSize);

        return push(ids, "reconcile_due_at", lease);
    }

    // --------------------------------------------------------------- refunds

    /**
     * Failed orders whose verification grace period has elapsed. Same claim and
     * lease shape as {@link #leaseDue}; this one asks "were we right to fail it?"
     */
    @Transactional
    public List<String> leaseForVerification(int batchSize, Duration lease) {
        List<String> ids = jdbc.queryForList("""
                SELECT order_id FROM orders
                 WHERE status = 'FAILED' AND verify_due_at <= now()
                 ORDER BY verify_due_at
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, String.class, batchSize);

        return push(ids, "verify_due_at", lease);
    }

    private List<String> push(List<String> ids, String column, Duration lease) {
        if (ids.isEmpty()) {
            return ids;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = seconds(lease);
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        jdbc.update(("""
                UPDATE orders
                   SET %s = now() + make_interval(secs => ?),
                       updated_at = now()
                 WHERE order_id IN (%s)
                """).formatted(column, placeholders), args);
        return ids;
    }

    /**
     * Not paid yet, but the schedule has more checks left. Push the next one out
     * and record that this attempt is spent.
     */
    public void rescheduleVerification(String orderId, Duration next) {
        jdbc.update("""
                UPDATE orders
                   SET verify_due_at = now() + make_interval(secs => ?),
                       verify_attempts = verify_attempts + 1,
                       updated_at = now()
                 WHERE order_id = ? AND status = 'FAILED'
                """, seconds(next), orderId);
    }

    /** The schedule is exhausted, or the provider confirmed the failure. Stop looking. */
    public void clearVerification(String orderId) {
        jdbc.update("""
                UPDATE orders SET verify_due_at = NULL, updated_at = now()
                WHERE order_id = ? AND status = 'FAILED'
                """, orderId);
    }

    /**
     * We were wrong: the order is FAILED but the customer was charged.
     * <p>
     * Guarded on {@code refund_state IS NULL} so a contradicting webhook and the
     * verification sweep can both discover the same overcharge without queueing
     * two refunds for it.
     *
     * @return true if this caller is the one that flagged it
     */
    public boolean requireRefund(String orderId, String paymentRef) {
        int rows = jdbc.update("""
                UPDATE orders
                   SET refund_state = 'REQUIRED',
                       payment_ref = COALESCE(?, payment_ref),
                       verify_due_at = NULL,
                       updated_at = now()
                 WHERE order_id = ? AND status = 'FAILED' AND refund_state IS NULL
                """, paymentRef, orderId);
        return rows == 1;
    }

    /**
     * Claim refunds to execute, and lease them.
     * <p>
     * The lease is what makes this crash-safe. A worker that dies after claiming
     * leaves the row IN_PROGRESS, and without a deadline on that claim nothing
     * would ever pick it up again -- money owed, invisible, forever. An expired
     * lease brings the row back into this query.
     */
    @Transactional
    public List<String> claimRefunds(int batchSize, Duration lease) {
        List<String> ids = jdbc.queryForList("""
                SELECT order_id FROM orders
                 WHERE refund_state = 'REQUIRED'
                    OR (refund_state = 'IN_PROGRESS' AND refund_due_at <= now())
                 ORDER BY updated_at
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, String.class, batchSize);

        if (ids.isEmpty()) {
            return ids;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = seconds(lease);
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        jdbc.update("""
                UPDATE orders
                   SET refund_state = 'IN_PROGRESS',
                       refund_due_at = now() + make_interval(secs => ?),
                       updated_at = now()
                 WHERE order_id IN (%s)
                """.formatted(placeholders), args);
        return ids;
    }

    public void settleRefund(String orderId, RefundState outcome, String refundRef) {
        jdbc.update("""
                UPDATE orders
                   SET refund_state = ?,
                       refund_ref = COALESCE(?, refund_ref),
                       refund_due_at = NULL,
                       updated_at = now()
                 WHERE order_id = ?
                """, outcome.name(), refundRef, orderId);
    }

    /** Hand a stuck refund back to the queue after a failed attempt. */
    public void releaseRefund(String orderId) {
        jdbc.update("""
                UPDATE orders
                   SET refund_state = 'REQUIRED', refund_due_at = NULL, updated_at = now()
                 WHERE order_id = ? AND refund_state = 'IN_PROGRESS'
                """, orderId);
    }
}
