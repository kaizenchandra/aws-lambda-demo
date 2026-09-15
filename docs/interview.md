# Senior engineer and solution architect interview guide

## Design questions

**Why AWS Lambda?** The acceptance API and fulfillment work are short, independent and bursty. Lambda scales execution
independently and removes server patching. We accept cold-start variability, invocation limits and careful concurrency
management.

**Why microservices?** Ordering and Fulfillment own different decisions and failure domains. Customers can place orders
while receipt storage is unavailable. Two services are sufficient for this scope; splitting every noun would create
operational cost without independence.

**Why DDD, and how were bounded contexts identified?** We separated the language and invariants of accepting an order
from fulfilling it. Ordering owns pricing, order lines and acceptance. Fulfillment owns pending/completed registration
and immutable receipt identity. Contexts own their tables and interact through integration contracts.

**How does Clean Architecture help?** Business rules compile without AWS or Spring. Dependencies point inward; the
application orchestrates ports and adapters provide details. A database or runtime change does not require rewriting
order invariants.

**How does Hexagonal Architecture improve testability?** An inbound port exposes a use case; outbound ports describe
needed capabilities such as repository transactions and immutable receipt storage. Unit tests replace these ports with
Mockito and test decisions quickly; adapter tests use actual LocalStack service APIs.

**Why SQS and SNS?** SQS buffers work, supports controlled concurrency, retry, retention and DLQs. SNS distributes
committed integration events to independently owned queues. A single consumer could use SQS alone, but the completion
feed and subscriber boundary make fan-out meaningful here.

**How is duplicate delivery handled?** The fulfillment aggregate stores the source event ID and receipt-content
fingerprint. A duplicate completed event returns success without creating a new receipt or completion event. Conflicting
content under the same aggregate is rejected.

**How is API idempotency implemented?** Customer principal plus request key deterministically identifies an order. A
canonical request fingerprint detects key reuse with different items. A conditional transaction creates both order and
outbox. Concurrent callers reread the winner; SDK/network failures can be retried with the same key.

**What happens when Lambda fails?** For a record-level error, the handler returns that record's message ID. Lambda
leaves it for retry after visibility expires. A runtime crash can retry a whole batch; durable idempotency protects
already completed work. Persistent failures enter a processing DLQ.

**How does the DLQ work?** SQS redrives messages after the configured receive threshold, with longer retention for
investigation. SNS subscription delivery DLQs and the schedule delivery DLQ cover different failure boundaries.
Operators fix the cause and replay original event IDs at a controlled rate.

**How are partial batches handled?** Each SQS record has its own try/catch boundary; only failed IDs appear in
`batchItemFailures`. Terraform enables `ReportBatchItemFailures`. Successfully processed records are acknowledged even
when another record is poison.

**How is concurrency controlled?** Reserved concurrency caps functions; the SQS mapping cap protects fulfillment
capacity. DynamoDB conditions protect aggregates. There is no in-memory distributed lock, and no exclusive lease that
could strand a fulfillment after a crash.

**How are spikes handled?** API throttling and Lambda concurrency contain ingress pressure. Durable queues absorb
asynchronous bursts. Monitor oldest-message age and outbox age, then raise consumption only if downstream capacity
supports it. The scheduled relay has a deliberate throughput ceiling.

**What consistency guarantees exist?** Order plus outbox and fulfillment completion plus outbox are atomic within their
owned DynamoDB tables. Strong reads resolve conditional conflicts. GSI publication and cross-context state are eventual.
Network delivery is at least once, not exactly once.

**What if persistence succeeds but publication fails?** The pending outbox row is durable and the next relay invocation
retries it. Publish happens before marking delivered, so an acknowledgement failure produces a duplicate rather than a
lost event.

**What if S3 succeeds and DynamoDB fails?** The receipt remains under a deterministic key. Retry verifies its digest,
reuses it and completes the aggregate transaction. Completion is never committed before the receipt exists.

**How is the system monitored?** Structured logs and EMF expose correlation IDs, processing failures, duplicates,
durations and publication age. CloudWatch alarms cover Lambda errors/throttles, queues and DLQs. Business metrics are
operational approximations; durable records are authoritative.

**How is it secured?** Signed IAM-authenticated API requests, owner-scoped reads, server-side pricing and input
validation, separate least-privilege execution roles, scoped SNS queue policies, encrypted storage, a private receipt
bucket and safe logs. Local authentication bypass is explicitly local-only. Staging must verify real IAM and KMS
behavior.

**How are Java cold starts managed?** Direct handlers initialize a small non-web Spring context once per execution
environment. SDK clients and serialization infrastructure are reused. The ZIP avoids a servlet container and nested
executable Boot packaging. Measure memory/CPU options and provisioned concurrency before adding more complex
optimizations.

**How would this evolve at 10× and 100× traffic?** At 10×, measure backlog and latency, tune memory, batch size,
concurrency and relay capacity. At 100×, revisit outbox sharding and stream-assisted publication, hot-key distribution,
retention and downstream subscriber capacity. Add independent release artifacts when service teams need separate release
cadence. Payment and stock introduce new bounded contexts and compensation, not shared tables.

**When should Lambda be replaced by ECS/EKS?** Sustained load, long jobs, many persistent connections or strict latency
floors may favor long-running services. ECS/Fargate is often the simplest managed container option. EKS is justified
when Kubernetes capabilities and existing platform expertise outweigh its operating cost. Preserve business cores and
replace runtime adapters.

## 30-Second Architecture Explanation

This system accepts digital-product purchase orders synchronously and fulfills them asynchronously. Ordering and
Fulfillment have pure Java domain models, separate DynamoDB tables and explicit ports. Lambda handlers reuse a small
Spring Boot context. Transactional outboxes publish through SNS and SQS; conditional state transitions and immutable S3
receipts make duplicate processing safe. Terraform and LocalStack support deployment and end-to-end tests, with IAM
boundaries, structured logs and DLQs for operations.

## 60-Second Architecture Explanation

I designed two bounded contexts around independent business decisions: accepting a correctly priced order and completing
its fulfillment registration. API Gateway authenticates clients, and an Ordering Lambda atomically writes the order and
an outbox event to DynamoDB. A relay sends that event through SNS to a fulfillment SQS queue. The Fulfillment Lambda
records a pending aggregate, writes an immutable S3 receipt, then atomically completes its state and creates a
completion event.

The important design choice is assuming duplicate delivery and process termination at every boundary. API idempotency
keys, stored event fingerprints, conditional writes and deterministic receipt keys let retries recover without repeating
business transitions. Partial batch responses isolate poison records, while DLQs and CloudWatch signals support
operations. The Java domain is independent of AWS and Spring; unit tests exercise rules, and LocalStack tests exercise
adapters and the deployed workflow. Production IAM, load, tracing and rollback still need staging evidence.

## 2-Minute Solution Architect Explanation

The business problem is reliable order acceptance without coupling customer availability to downstream fulfillment. The
implemented scope is a contracted-customer digital catalog: this reference accepts purchase orders and registers
fulfillment receipts; it does not claim to charge cards or manage physical inventory.

I identified two bounded contexts from their language and invariants. Ordering owns catalog pricing, order lines, totals
and acceptance. Fulfillment owns its pending-to-completed lifecycle and receipt identity. Each context owns its
persistence. Clean Architecture keeps both domains framework-free; application services depend on ports, and AWS
adapters implement those ports. Direct Java Lambda handlers reuse a small non-web Spring Boot context and AWS SDK
clients.

A signed API request enters API Gateway and creates an order plus outbox entry in one DynamoDB transaction. Scheduled
relays publish integration events through SNS into SQS queues. The fulfillment worker persists pending state, writes an
immutable S3 receipt, and commits completion plus its outgoing event atomically. That ordering resolves the
database-to-message and S3-to-database failure windows without pretending that different AWS services share a
transaction.

The delivery model is at least once. Customer-scoped idempotency keys protect API retries. Event IDs, fingerprints and
optimistic conditions protect concurrent consumers. Receipt keys and contents are deterministic; retries reuse the same
object. Partial SQS responses retry only failed records, with a DLQ for persistent failures. Reserved concurrency,
mapping limits and queues contain traffic spikes; outbox and queue age expose backlog.

Security includes authenticated ingress, owner checks, separate scoped roles, encrypted storage and private receipts.
Structured logs, business correlation, CloudWatch metrics and alarms provide operational visibility; deeper distributed
tracing is a documented extension. Terraform defines the infrastructure, and tests progress from pure domain tests to
LocalStack adapters and deployed E2E failure cases. The trade-offs are eventual consistency, Java cold starts, relay
throughput and extra operational resources. Before production, I would validate IAM, KMS, load, alerts, restore and
rollback in isolated AWS staging, then promote the same versioned artifact with monitored aliases.
