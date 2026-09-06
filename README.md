# Checkout Service

Takes an order, starts a payment, and resolves that order whether or not the
payment provider's webhook ever arrives.

## The problem

The provider confirms payments asynchronously. Webhooks get delayed, dropped and
retried, so an order that simply waits for one can wait forever.

So we put a deadline on the wait. If nothing has resolved an order within five
seconds, a job goes and asks the provider directly.

```
POST /checkout
  |
  |-- save order PENDING, reconcile_due_at = now() + 5s     <- committed FIRST, so the
  |                                                            safety net exists even if
  |-- call the payment API (initiate)                          the next call never returns
  |
  '-- 202 { orderId, status: PENDING }        nothing blocks on the provider


        webhook arrives first              no webhook by 5s
                |                                 |
                v                                 v
        order -> PAID / FAILED            the job asks the provider
        reconcile_due_at = NULL                   |
        the job never sees the row                |-- PAID -> order PAID
                                                  |
                                                  '-- anything else -> order FAILED
                                                              |
                                                       ...which may be wrong.
                                                       See "The edge case" below.
```

## Design decisions

**No message broker.** The requirement is "do this thing five seconds from now",
and that is a timer, not a transport. Kafka has no delayed delivery, so using it
here would mean writing pause/seek machinery that would end up being most of the
code. A `reconcile_due_at` column does the same job in one line, and because the
timer lives in the same row as the state, the two cannot disagree. A crash costs
nothing: the row is still there when the process comes back.

**The order id is the only identifier.** It is the client's idempotency key, the
provider's correlation reference, and the provider's idempotency key. A webhook
is looked up by order id, never by the reference the provider hands back --
otherwise a fast webhook that arrives before `initiate` returns cannot be
matched to anything, and a PAID event gets dropped.

**A timeout on `initiate` is not a failure.** It is one of three outcomes:
accepted, definitively rejected, or unknown. An unknown outcome leaves the order
PENDING with its timer armed, so reconciliation finds out what really happened.
Marking it FAILED would risk telling a charged customer their order did not go
through.

**One guarded UPDATE does most of the work:**

```sql
UPDATE orders SET status = ? WHERE order_id = ? AND status = 'PENDING'
```

Zero rows updated means somebody already resolved the order, and the caller
stops. That single statement settles the webhook-versus-job race, dedupes
repeated webhooks, and makes the job safe to re-run after a crash.

**Claim, then work.** Each job leases the rows it claims (`FOR UPDATE SKIP
LOCKED`, then push the timer out) and commits before making any network call, so
row locks are never held across a request to the provider. If a worker dies, the
lease expires and another pass retries.

## The edge case

The deadline can be wrong. A payment the provider will not confirm within five
seconds may settle a moment later, so the job can fail an order the customer was
actually charged for. A five-second deadline manufactures this case; it can only
be detected and corrected, not designed away.

Two things detect it, and they fail differently:

1. **A late webhook contradicts us.** A PAID event for an order we already failed
   is the provider correcting us. Nearly free, and usually first -- but it only
   fires if the provider speaks up.
2. **A verification sweep.** After failing an order we ask again at 10s, 1m and
   10m. One check would only catch a payment that settled at that exact moment;
   the tail catches a payment that settles quietly with no webhook at all.

Both converge on the same guarded update (`refund_state IS NULL`), so an
overcharge is never queued for two refunds. The order stays FAILED and picks up
a `refund_state`, which the refund job drives to REFUNDED.

## Running it

Java 21 and Docker. Maven comes from the wrapper.

```bash
docker-compose up -d          # PostgreSQL on 5432
./mvnw spring-boot:run        # service on 8080
```

Flyway builds the schema on startup.

```bash
curl -X POST localhost:8080/checkout \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ord-1","amount":19.99,"currency":"GBP","scenario":"FAST"}'

curl localhost:8080/orders/ord-1
```

| Method | Path                | Purpose                                                |
|--------|---------------------|--------------------------------------------------------|
| POST   | `/checkout`         | Place an order. `202` with the order id.               |
| GET    | `/orders/{orderId}` | Current status.                                        |
| POST   | `/webhooks/payment` | Provider callbacks. Always `200`, even for duplicates. |

## Seeing each path

A fake provider stands in for the real one. The `scenario` field on the checkout
request picks its behaviour, so every branch is reachable without an account
anywhere:

| Scenario    | What the provider does                    | What you should see                                        |
|-------------|-------------------------------------------|------------------------------------------------------------|
| `FAST`      | webhook at 0.4s                           | PAID, `resolvedBy: WEBHOOK` — the job never sees it         |
| `SLOW`      | settles at 1s, webhook at 10s             | PAID by the job at 5s; the late webhook is a no-op          |
| `NEVER`     | settles at 1s, never calls back           | PAID by the job at 5s                                       |
| `STUCK`     | never settles                             | FAILED by the job, then confirmed by the verification tail  |
| `DECLINED`  | declines at 0.3s, webhook                 | FAILED, `resolvedBy: WEBHOOK`                               |
| `REJECT`    | rejects the initiate call outright        | FAILED, `resolvedBy: INITIATE`, no waiting at all           |
| `UNKNOWN`   | initiate times out, but the payment lands | PENDING, then PAID by the job — a timeout is not a failure  |
| `LATE_PAID` | settles at 8s, never calls back           | FAILED at 5s, then refunded once the sweep spots the charge |
| `LATE_HOOK` | settles at 8s, webhook at 9s              | FAILED at 5s, then refunded when the webhook contradicts us |

```bash
docker exec checkout-postgres psql -U checkout -d checkout -c \
  "SELECT order_id, status, resolved_by, refund_state FROM orders ORDER BY created_at"
```

## Tests

```bash
docker-compose up -d
./mvnw test
```

43 tests, run against real PostgreSQL in a separate `checkout_test` database.
The guarantees worth testing -- the guarded UPDATE, `SKIP LOCKED` claiming, the
CHECK constraints -- only exist in the database, so testing against an in-memory
substitute would be testing something else.

- `OrderRepositoryTest` — the concurrency guarantees, including that a second
  resolver is told it lost and that an abandoned refund claim is reclaimed
- `CheckoutFlowTest` — the race, idempotent checkout, an unknown `initiate`
  staying PENDING, a provider outage mid-job
- `RefundFlowTest` — both discovery paths, one refund from two discoveries, a
  payment that settles late enough to need the tail

## Layout

```
api/         HTTP surface
service/     orchestration, and the webhook-contradiction check
reconcile/   ReconciliationJob (the deadline), RefundJob (cleaning up after it)
store/       every SQL statement, including the one guarded transition
payment/     the provider port, and the fake that stands in for it
domain/      Order, OrderStatus, RefundState
```

## Trade-offs, and what I would do next

**The webhook endpoint is unauthenticated.** Anyone who can reach it can mark any
order PAID. Real providers sign their callbacks; this would need HMAC
verification and a replay window before it went anywhere near a public network.
It is the first thing I would add.

**No metrics.** The single most useful signal here is the ratio of orders
resolved by webhook versus by the job. If the job starts winning, the provider's
webhooks are broken and you want to know before customers tell you. Nothing
measures that today.

**No jitter or circuit breaker.** If the provider goes down, every pending order
comes due at once and hammers a dead service. Backoff needs jitter, and the
status call needs an explicit timeout — without one a hanging provider parks a
job thread indefinitely.

**Both jobs share one scheduler thread**, so refunds queue behind reconciliation.
`SKIP LOCKED` already allows several instances to run safely; there is just no
concurrency within one instance.

**Five seconds is a card-payment number.** 3-D Secure, UPI and bank redirects
routinely take a minute or more while the customer is on someone else's page.
Those orders would start reconciling while the customer is still typing an OTP,
so the window should be a property of the payment method rather than a constant.

**A replayed checkout with a different amount is silently accepted** — the insert
conflict is ignored and the original order is returned. Proper idempotency
compares a request fingerprint and rejects a mismatched replay.

**The customer is not told.** They see FAILED for the window between the deadline
and the refund. This service guarantees the money comes back; it does not notify
anyone, and it does not try to revive the order instead of refunding it. Both are
product decisions rather than missing code.

Out of scope throughout: cart, pricing, inventory, and customer-requested
refunds. This service ends at "the order is paid for".

## How this was built

Built with Claude Code, design first — no code was written until the API
contract, the fallback mechanism, the storage and the deadline behaviour were
settled.

The first design used Kafka to carry the five-second delay. That was dropped
once it became clear the requirement is a timer, not a transport, and Kafka has
no delayed delivery — emulating it would have been most of the code.

Two defects surfaced in review and were fixed:

- A refund claim had no lease, so a worker that died mid-refund stranded the
  money as `IN_PROGRESS` forever.
- Verification asked the provider once, ten seconds after failing an order. A
  payment settling later than that single moment was never noticed.

Both are on the money path, and both are the kind of thing that only shows up if
you read generated code as adversarially as you would a colleague's.
