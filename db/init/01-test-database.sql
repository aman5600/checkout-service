-- The test suite runs against real PostgreSQL, in its own database so a test
-- run never touches whatever you were poking at by hand.
CREATE DATABASE checkout_test OWNER checkout;
