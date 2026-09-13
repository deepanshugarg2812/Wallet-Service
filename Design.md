# Wallet & P2P Transfer Service

## 1. Overview

This project implements a wallet and peer-to-peer money transfer service using:

* Java
* Spring Boot
* PostgreSQL
* JPA/Hibernate
* Docker / Docker Compose
* JSON structured logging
* Correlation IDs
* Go-based concurrency/stress testing
* Render for deployment

### Live Service

**Base URL:**

https://wallet-service-pt2d.onrender.com

The service is designed around a core principle:

> PostgreSQL is the authoritative source of truth for wallet balances and transfer state, and all money movement is performed atomically inside a database transaction.

---

# 2. APIs

## 2.1 Create / Get Wallet

### Endpoint

```http
POST /wallets?userId={userId}
```

The operation is idempotent for a user.

A unique database constraint on `user_id` guarantees that concurrent requests cannot create multiple wallets for the same user.

### Example

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=101"
```

Another user:

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=102"
```

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=103"
```

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=104"
```

### Expected behavior

Calling the same endpoint multiple times for the same user returns the same wallet rather than creating a new wallet.

---

# 3. Get Wallet

### Endpoint

```http
GET /wallets/{walletId}
```

### Example

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1001"
```

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1002"
```

Example response:

```json
{
  "id": 1001,
  "userId": 101,
  "amountPaisa": 1000000,
  "status": "ACTIVE"
}
```

All monetary values are represented as integer paise.

For example:

```text
1000000 paise = ₹10,000
100 paise     = ₹1
```

No floating-point representation is used for money.

---

# 4. Create Transfer

### Endpoint

```http
POST /transfers
```

### Request

```json
{
  "walletFromId": 1001,
  "walletToId": 1002,
  "amountPaisa": 100,
  "idempotencyKey": "test-transfer-001"
}
```

### cURL

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 100,
    "idempotencyKey": "test-transfer-001"
  }'
```

The response contains the transfer ID and status.

Example:

```json
{
  "id": "abc-123",
  "walletFromId": 1001,
  "walletToId": 1002,
  "amountPaisa": 100,
  "status": "COMPLETED",
  "idempotencyKey": "test-transfer-001"
}
```

---

# 5. Idempotency

Every transfer requires an idempotency key.

The database contains:

```text
UNIQUE(idempotency_key)
```

The transfer creation flow is:

```text
Request
   |
   v
Calculate request hash
   |
   v
INSERT transfer with idempotency key
   |
   +---- Insert succeeds
   |          |
   |          v
   |     Debit sender
   |          |
   |          v
   |     Credit receiver
   |          |
   |          v
   |     Mark COMPLETED
   |
   +---- Conflict
              |
              v
       Load existing transfer
              |
        +-----+------+
        |            |
   Same request   Different request
        |            |
        v            v
   Return old      409 Conflict
   result
```

The idempotency record and wallet balance changes occur inside the **same database transaction**.

This prevents the situation where an idempotency record exists but the money movement was not committed, or vice versa.

---

# 6. Test Idempotent Retry

Run the same request multiple times:

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 100,
    "idempotencyKey": "test-transfer-001"
  }'
```

Run it again:

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 100,
    "idempotencyKey": "test-transfer-001"
  }'
```

Both requests should return the same transfer ID.

The sender should only be debited once.

---

# 7. Idempotency Conflict

The same idempotency key cannot be reused for a different request.

For example, after using:

```text
test-transfer-001
amount = 100
```

the following request must fail:

```bash
curl -i -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 999,
    "idempotencyKey": "test-transfer-001"
  }'
```

Expected result:

```text
HTTP 409 Conflict
```

This protects against accidental reuse of an idempotency key for a different operation.

---

# 8. Get Transfer

### Endpoint

```http
GET /transfers/{transferId}
```

For example, if the previous response returned:

```text
abc-123
```

run:

```bash
curl "https://wallet-service-pt2d.onrender.com/transfers/abc-123"
```

---

# 9. Insufficient Balance

Wallet `1004` can be seeded with:

```text
100000 paise = ₹1,000
```

Attempting to transfer more than the available balance:

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1004,
    "walletToId": 1001,
    "amountPaisa": 200000,
    "idempotencyKey": "insufficient-funds-001"
  }'
```

The transfer should be declined.

The sender balance must remain unchanged.

Verify:

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1004"
```

---

# 10. Reverse Direction Transfer

To test both directions:

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1002,
    "walletToId": 1001,
    "amountPaisa": 100,
    "idempotencyKey": "reverse-direction-001"
  }'
```

This is also useful for concurrency testing where:

```text
A -> B
B -> A
```

occur simultaneously.

---

# 11. Health Check

The application exposes the Spring Boot Actuator health endpoint:

```bash
curl "https://wallet-service-pt2d.onrender.com/actuator/health"
```

Expected:

```json
{
  "status": "UP"
}
```

This can be used by the hosting platform or load balancer to determine whether the service is healthy.

---

# 12. Database Schema

## Wallet

Conceptually:

```text
wallet
--------------------------------
id                  PK
user_id             UNIQUE
amount_paisa
status
created_at
updated_at
```

Important constraint:

```sql
UNIQUE(user_id)
```

This is required for race-free wallet creation.

Wallet creation uses:

```sql
INSERT INTO wallet (...)
VALUES (...)
ON CONFLICT (user_id) DO NOTHING;
```

This avoids the unsafe pattern:

```text
SELECT wallet
   |
   | not found
   v
INSERT wallet
```

because two concurrent requests can both observe "not found".

---

# 13. Transfer Schema

```text
transfers
--------------------------------
id                       PK
wallet_from_id
wallet_to_id
amount_paisa
status
transaction_date_time
idempotency_key          UNIQUE
request_hash
```

Important constraint:

```sql
UNIQUE(idempotency_key)
```

This makes idempotency a database-enforced invariant rather than an application-level assumption.

---

# 14. Concurrency and Money Movement

The sender balance is updated using an atomic conditional update:

```sql
UPDATE wallet
SET amount_paisa = amount_paisa - :amount,
    updated_at = NOW()
WHERE id = :walletId
  AND amount_paisa >= :amount;
```

The number of affected rows is checked.

### `1 row affected`

The debit succeeded.

### `0 rows affected`

The transfer cannot proceed because the wallet either does not exist or does not have sufficient balance.

The receiver is then credited:

```sql
UPDATE wallet
SET amount_paisa = amount_paisa + :amount,
    updated_at = NOW()
WHERE id = :walletId;
```

Both operations occur in one database transaction.

Therefore:

```text
Debit succeeds
    +
Credit succeeds
    +
Transfer status updated
    =
COMMIT
```

If any required operation fails:

```text
ROLLBACK
```

The money movement is therefore atomic.

---

# 15. Why Conditional UPDATE Instead of Application-Level Read/Write?

An unsafe implementation would be:

```text
SELECT balance
      |
      v
balance >= amount?
      |
      v
balance = balance - amount
      |
      v
UPDATE
```

Two concurrent requests can both read the same balance and both decide that enough money exists.

Instead, the balance condition is evaluated as part of the database update itself:

```sql
UPDATE ...
WHERE balance_paisa >= amount
```

PostgreSQL serializes concurrent updates to the same row, preventing the sender from going negative.

---

# 16. Conservation Invariant

For every successful transfer:

```text
Sender final balance
    = Sender initial balance - amount

Receiver final balance
    = Receiver initial balance + amount
```

Therefore:

```text
Total money before transfer
    =
Total money after transfer
```

For example:

```text
Before:
A = 1000
B = 500

Total = 1500

Transfer A -> B = 100

After:
A = 900
B = 600

Total = 1500
```

The same invariant is validated during concurrent testing.

---

# 17. Concurrency Testing

A Go-based stress-test client is included to generate concurrent requests.

Example:

```bash
go run . idempotency 10000
```

This sends 10,000 concurrent requests using the same idempotency key.

Expected:

```text
Requests:             10000
Unique transfer IDs:  1
Actual money movement: 1
```

This validates the idempotency race.

A second test:

```bash
go run . overdraft 10000
```

generates many independent transfers against a wallet with limited balance.

The test validates:

```text
No negative balance
+
Conservation
+
Correct number of successful transfers
```

The important point is that the test does not merely verify HTTP responses. It reads the final wallet state and validates the actual database invariants.

---

# 18. Docker

The application is containerized using a multi-stage Docker build.

Build stage:

```text
Maven + JDK
    |
    v
Build Spring Boot JAR
```

Runtime stage:

```text
JRE
    |
    v
Run application
```

The runtime container runs the application as a non-root user.

Docker Compose contains:

```text
wallet-service
      |
      v
PostgreSQL
```

PostgreSQL is exposed locally on a different host port to avoid conflicts with an existing local PostgreSQL installation.

Inside the Docker network, the application connects to:

```text
postgres:5432
```

rather than `localhost`.

---

# 19. Deployment

The application is deployed on Render.

Production database configuration is supplied through environment variables rather than hard-coded credentials:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
PORT
```

The application therefore uses the same container image across environments while configuration changes through environment variables.

Live service:

https://wallet-service-pt2d.onrender.com

---

# 20. Logging

The application uses Logback with JSON structured logging.

Important transfer events are logged, including:

* Transfer request received
* Idempotent replay
* Idempotency conflict
* Insufficient balance / declined transfer
* Transfer completed

Example conceptual log:

```json
{
  "@timestamp": "2026-09-13T10:00:00Z",
  "level": "INFO",
  "message": "Transfer completed",
  "correlationId": "demo-123"
}
```

A correlation ID is propagated through the request using:

```text
X-Correlation-Id
```

Example:

```bash
curl -i -X POST \
  "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-123" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 100,
    "idempotencyKey": "correlation-test-001"
  }'
```

This makes it possible to correlate an API request with its corresponding application logs.

---

# 21. Consistency vs Availability

Money movement is treated as a consistency-first operation.

PostgreSQL is the authoritative transactional store.

During a database partition or primary failure, the service should not accept uncertain money writes against stale state merely to remain available.

The priority is:

```text
Correct balance
    >
Availability of money movement
```

Read replicas can be used for non-critical or eventually consistent reads, while the primary remains the authority for transactional writes.

---

# 22. Scaling Strategy

The initial implementation deliberately avoids distributed complexity.

For a larger system, the scaling path would be:

```text
1. Vertical PostgreSQL scaling
        ↓
2. Query/index optimization
        ↓
3. Connection pool tuning
        ↓
4. Read replicas
        ↓
5. Transfer-history archival
        ↓
6. Identify hot-wallet contention
        ↓
7. Partition/shard if genuinely required
```

A transfer should ideally remain within one transactional authority rather than requiring a distributed database transaction across multiple independent database nodes.

---

# 23. Dashboard / Analytics — Future Improvement

A dashboard was intentionally kept out of the current implementation to focus the assignment on the core correctness requirements.

A production dashboard could expose:

```text
Total transfers
Successful transfers
Declined transfers
Transfer volume
Average latency
p95 / p99 latency
Error rate
Active wallets
Top transaction volumes
```

There are two possible approaches.

### Option A — Read Replica

Create a PostgreSQL read replica and run analytics queries against the replica.

```text
                    +--> Primary PostgreSQL
                    |       |
Application --------+
                    |
                    +--> Read Replica
                            |
                            v
                       Dashboard
```

This prevents expensive analytical queries from competing with transactional money movement on the primary.

This approach is appropriate for relatively simple operational dashboards.

### Option B — Event-Based Analytics

Publish domain events such as:

```text
TRANSFER_CREATED
TRANSFER_DEBITED
TRANSFER_CREDITED
TRANSFER_DECLINED
TRANSFER_COMPLETED
IDEMPOTENT_REPLAY
```

to an event/streaming system.

The analytics pipeline can then maintain separate read models optimized for dashboard queries.

Conceptually:

```text
                 PostgreSQL
                     |
                     |
                Domain Event
                     |
                     v
              Event / Stream
                     |
             +-------+-------+
             |               |
             v               v
       Analytics Store    Monitoring
             |
             v
         Dashboard
```

For a higher-scale system, the event-based approach provides better separation between transactional workloads and analytics workloads.

The key design principle is:

> Analytics should not run expensive queries directly against the transactional path if that workload can affect money movement latency or availability.

---

# 24. Authentication — Future Improvement

The current implementation keeps authentication intentionally simple to focus on the wallet and transfer correctness requirements.

A production implementation would introduce:

```text
Client
   |
   v
Authentication / Identity Provider
   |
   v
JWT
   |
   v
API
```

The JWT would contain the authenticated user identity and relevant authorization claims.

The service would derive the user identity from the authenticated token rather than trusting a user ID supplied by the client.

Authorization could then be implemented using roles/scopes such as:

```text
USER
ADMIN
OPERATIONS
ANALYTICS
```

JWT validation and authorization are intentionally documented as future production improvements rather than being part of the core transactional implementation.

---

# 25. Dashboard and Authentication Trade-off

The assignment was intentionally scoped to prioritize:

```text
Correctness
    >
Concurrency safety
    >
Idempotency
    >
Deployment
    >
Observability
```

rather than spending implementation time on secondary platform features.

Therefore:

### Implemented

* Wallet creation
* Race-free wallet creation
* Wallet balance retrieval
* P2P transfers
* Atomic debit
* Atomic credit
* No overdraft
* Conservation
* Idempotency
* Idempotency conflict detection
* JSON logging
* Correlation IDs
* Docker
* Docker Compose
* Health endpoint
* Cloud deployment
* Concurrent stress testing

### Future improvements

* JWT authentication
* Role-based authorization
* Production identity provider
* Analytics dashboard
* Event-based analytics
* Read-replica analytics
* Advanced metrics/monitoring
* Distributed tracing
* Rate limiting
* Transfer reversal workflow
* Historical transaction archival

---

# 26. Key Design Decisions

## PostgreSQL as source of truth

Chosen because the wallet transfer operation requires:

* Atomicity
* Strong consistency
* Transactions
* Row-level locking
* Constraints
* Conditional updates

## Integer paise

Avoids floating-point precision problems.

## Database-enforced uniqueness

Critical invariants are enforced by PostgreSQL:

```sql
UNIQUE(user_id)
UNIQUE(idempotency_key)
```

Application logic alone is not relied upon for concurrency correctness.

## Conditional balance update

Chosen instead of:

```text
SELECT balance
UPDATE balance
```

because the condition and update are performed atomically by PostgreSQL.

## Single transactional database authority

The debit and credit remain in one database transaction instead of introducing distributed transactions unnecessarily.

## Stateless application

The application does not maintain correctness state in local memory, allowing multiple application instances to run behind a load balancer.

---

# 27. Final Correctness Properties

The system is designed to maintain the following invariants:

### No overdraft

```text
balance >= 0
```

for every wallet.

### Conservation

```text
sum(all wallet balances)
```

does not change because of a transfer.

### Exactly-once idempotency

For:

```text
same idempotency key
+
same request
```

there is exactly one money movement.

### Idempotency conflict

For:

```text
same idempotency key
+
different request
```

the request is rejected.

### Race-free wallet creation

For N concurrent creation requests for the same user:

```text
number of wallets = 1
```

### Atomic transfer

Either:

```text
debit + credit + transfer state
```

all commit, or the transaction rolls back.

---

# 28. Demo Checklist

For a live demonstration:

### 1. Health

```bash
curl "https://wallet-service-pt2d.onrender.com/actuator/health"
```

### 2. Create wallets

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=101"
```

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/wallets?userId=102"
```

### 3. Check balances

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1001"
```

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1002"
```

### 4. Transfer

```bash
curl -X POST "https://wallet-service-pt2d.onrender.com/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "walletFromId": 1001,
    "walletToId": 1002,
    "amountPaisa": 100,
    "idempotencyKey": "demo-transfer-001"
  }'
```

### 5. Repeat same request

Verify that the transfer ID remains identical.

### 6. Reuse key with different amount

Verify `409 Conflict`.

### 7. Run concurrency test

```bash
go run . idempotency 10000
```

Then:

```bash
go run . overdraft 10000
```

### 8. Verify final balances

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1001"
```

```bash
curl "https://wallet-service-pt2d.onrender.com/wallets/1002"
```

The final balances should satisfy conservation and no-overdraft invariants.

---

# 29. Conclusion

The primary goal of this implementation is not maximum feature count but **correctness under concurrency and failure**.

The critical design is:

```text
              HTTP Request
                   |
                   v
            Stateless API
                   |
                   v
          PostgreSQL Transaction
             /           \
            /             \
           v               v
     Idempotency       Wallet Updates
       Constraint       Atomic Debit
                        Atomic Credit
            \             /
             \           /
                  COMMIT
```

The database enforces uniqueness, the balance update is atomic, and the idempotency record and money movement share the same transaction boundary.

This provides a simple transactional foundation that can later be extended with authentication, analytics, read replicas, event-driven processing, monitoring, and horizontal scaling without weakening the core money-movement invariants.
