-- One table holds the order, its timers and its refund state together, so none
-- of them can drift apart from each other. A non-null *_due_at IS a live timer;
-- there is no scheduler state anywhere else.
CREATE TABLE orders (
    order_id         TEXT           PRIMARY KEY,
    status           TEXT           NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL,
    currency         TEXT           NOT NULL,
    payment_ref      TEXT,
    resolved_by      TEXT,

    -- While PENDING: "if nobody has resolved this by then, ask the provider".
    reconcile_due_at TIMESTAMPTZ,

    -- After the job FAILS an order: "go back and check we were right about that".
    verify_due_at    TIMESTAMPTZ,
    verify_attempts  INTEGER        NOT NULL DEFAULT 0,

    -- Set only when we failed an order the provider actually charged for.
    refund_state     TEXT,
    refund_ref       TEXT,
    refund_due_at    TIMESTAMPTZ,

    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT orders_status_chk CHECK (status IN ('PENDING', 'PAID', 'FAILED')),

    CONSTRAINT orders_refund_state_chk
        CHECK (refund_state IS NULL OR refund_state IN ('REQUIRED', 'IN_PROGRESS', 'REFUNDED', 'UNREFUNDABLE')),

    -- A resolved order never carries a live reconciliation timer.
    CONSTRAINT orders_timer_chk CHECK (status = 'PENDING' OR reconcile_due_at IS NULL),

    -- Only an order we failed can be awaiting a second look.
    CONSTRAINT orders_verify_chk CHECK (verify_due_at IS NULL OR status = 'FAILED'),

    -- A refund lease exists only while a worker holds the claim. Without this
    -- deadline, a worker that died mid-refund would strand the money forever.
    CONSTRAINT orders_refund_lease_chk
        CHECK (refund_due_at IS NULL OR refund_state = 'IN_PROGRESS')
);

-- Each job's only query, indexed so it touches just the live timers.
CREATE INDEX orders_due_idx ON orders (reconcile_due_at)
    WHERE status = 'PENDING';

CREATE INDEX orders_verify_idx ON orders (verify_due_at)
    WHERE status = 'FAILED' AND verify_due_at IS NOT NULL;

CREATE INDEX orders_refund_idx ON orders (refund_state)
    WHERE refund_state IN ('REQUIRED', 'IN_PROGRESS');

CREATE INDEX orders_refund_lease_idx ON orders (refund_due_at)
    WHERE refund_state = 'IN_PROGRESS';
