# Implementation phases

Complete implementation lives in the linked source files, not duplicated snippets. Paths below are repository-relative.
The evidence ledger in [validation.md](validation.md) supersedes provisional checkpoints.

## Phase 01 — Business Requirements

### Goal

Make durable digital purchase-order acceptance useful to contracted customers.

### Design Decisions

Two actors: authenticated customer and operator; payment and physical stock are outside scope.

### Architecture / Flow

Create → accept → fulfill → receipt → completion event.

### Files Created or Modified

docs/design.md

### Complete Implementation

Domain baseline and requirements are the complete phase artifact.

### How It Works

Agree on vocabulary and failure expectations before coding.

### How to Run

Read docs/design.md.

### How to Test

Compare requirements to docs/testing.md.

### Production Considerations

Confirm contractual meaning of a fulfillment receipt.

### Checkpoint

Requirements and invariants documented.

## Phase 02 — Domain Modeling & Bounded Contexts

### Goal

Give each business decision an owner.

### Design Decisions

Order and Fulfillment are separate aggregate roots with separate tables.

### Architecture / Flow

Ordering publishes accepted contract; Fulfillment consumes it.

### Files Created or Modified

ordering-core; fulfillment-core; docs/design.md

### Complete Implementation

Domain records, events and catalog service are implemented in core modules.

### How It Works

Invariants live in constructors and transitions.

### How to Run

./mvnw -pl ordering-core,fulfillment-core -am test

### How to Test

OrderTest and FulfillmentTest.

### Production Considerations

New payment/stock requirements need explicit context and compensation design.

### Checkpoint

Plain Java model and owned boundaries validated.

## Phase 03 — Architecture & Design

### Goal

Enforce inward dependencies and durable recovery.

### Design Decisions

Ports/adapters, outbox, aggregate inbox, conditional receipt writes.

### Architecture / Flow

API/SQS adapters → use cases → domain; outbound ports → AWS.

### Files Created or Modified

docs/design.md; docs/adr

### Complete Implementation

Eight Mermaid diagrams and decision records describe the implemented topology.

### How It Works

No cross-service transaction or synchronous database sharing.

### How to Run

Read docs/design.md and docs/adr.

### How to Test

python3 scripts/check-architecture.py

### Production Considerations

Treat cross-context reads as eventual.

### Checkpoint

Architecture baseline established before implementation.

## Phase 04 — Repository Structure

### Goal

Make context dependencies explicit in the build.

### Design Decisions

Four Maven modules; one reusable runtime ZIP and isolated deployed functions.

### Architecture / Flow

contracts ← cores ← runtime.

### Files Created or Modified

pom.xml; contracts/pom.xml; ordering-core/pom.xml; fulfillment-core/pom.xml; runtime/pom.xml

### Complete Implementation

Reactor POMs, dependency BOMs and ZIP assembly are complete.

### How It Works

Cores cannot compile against AWS/Spring because those dependencies are runtime-only.

### How to Run

./mvnw clean verify

### How to Test

python3 scripts/check-architecture.py

### Production Considerations

Separate deployment artifacts when release ownership demands it.

### Checkpoint

Reactor compilation and packaging verified.

## Phase 05 — Domain Layer

### Goal

Implement monetary and lifecycle invariants.

### Design Decisions

BigDecimal Money, priced OrderLine entities, immutable Order, versioned Fulfillment.

### Architecture / Flow

Catalog pricing → Order acceptance; PENDING → COMPLETED.

### Files Created or Modified

ordering-core/src/main; fulfillment-core/src/main

### Complete Implementation

Complete domain classes and local domain events live in these directories.

### How It Works

Constructors reject invalid identity, totals, quantities and states.

### How to Run

./mvnw -pl ordering-core,fulfillment-core -am test

### How to Test

OrderTest; FulfillmentTest.

### Production Considerations

Catalog changes are explicit releases; no client-supplied prices.

### Checkpoint

Domain unit tests verified.

## Phase 06 — Application Layer & Ports

### Goal

Orchestrate business use cases independently of AWS.

### Design Decisions

OrderUseCases and FulfillmentUseCases inbound; context repositories and ReceiptStorage outbound.

### Architecture / Flow

Validate → load/create → map domain event → transactional port call.

### Files Created or Modified

ordering-core/src/main; fulfillment-core/src/main

### Complete Implementation

OrderService and FulfillmentService fully implement use cases through injected ports.

### How It Works

Deterministic request identity and source fingerprints support replay.

### How to Run

./mvnw test

### How to Test

Mockito business-port tests.

### Production Considerations

Port contracts explicitly require atomic aggregate/outbox commits.

### Checkpoint

Application failure and replay tests verified.

## Phase 07 — Lambda Inbound Adapters

### Goal

Translate API and SQS protocols into use cases.

### Design Decisions

Direct RequestHandler, fixed error boundaries, no domain rules in handlers.

### Architecture / Flow

API Gateway → OrderHandler/query; SQS → FulfillmentHandler.

### Files Created or Modified

runtime/src/main/java/com/synechisveltiosi/commerce/ordering/adapter/in;
runtime/src/main/java/com/synechisveltiosi/commerce/fulfillment/adapter/in

### Complete Implementation

Handlers, structured responses and per-record partial failures are complete.

### How It Works

Injectable constructors enable handler tests without Spring.

### How to Run

./scripts/init-localstack.sh

### How to Test

HandlerTest; scripts/e2e.py.

### Production Considerations

API and worker deadlines differ; never leak exception messages.

### Checkpoint

Handler tests and deployed evidence are recorded in validation.md.

## Phase 08 — AWS Outbound Adapters

### Goal

Realize persistence and external side effects safely.

### Design Decisions

DynamoDB transactions, strong reads, conditional Put/Update; S3 If-None-Match.

### Architecture / Flow

Aggregate+outbox commit; immutable receipt before completion.

### Files Created or Modified

runtime/src/main/java/com/synechisveltiosi/commerce/ordering/adapter/out;
runtime/src/main/java/com/synechisveltiosi/commerce/fulfillment/adapter/out

### Complete Implementation

Concrete repositories and receipt adapter use AWS SDK 2.x.

### How It Works

Conditional conflicts reread durable winners; AWS failures propagate.

### How to Run

./mvnw -Pintegration verify

### How to Test

AwsAdaptersIT.

### Production Considerations

IAM must permit transaction underlying actions and scoped S3 operations.

### Checkpoint

Seven LocalStack adapter tests verified.

## Phase 09 — Event-Driven Communication

### Goal

Deliver explicit integration contracts through independently owned queues.

### Design Decisions

SNS accepted/completed topics, raw SQS subscriptions, scheduled outbox relays.

### Architecture / Flow

Order outbox → SNS → SQS → worker → completion outbox → SNS.

### Files Created or Modified

contracts; runtime/src/main/java/com/synechisveltiosi/commerce/platform/outbox; infrastructure/terraform/messaging.tf

### Complete Implementation

Typed envelope, publisher, relay and subscription resources are implemented.

### How It Works

Publish before marking delivered; unknown optional fields remain compatible.

### How to Run

./scripts/init-localstack.sh

### How to Test

AwsAdaptersIT workflow; scripts/e2e.py.

### Production Considerations

Version breaking changes and preserve event/correlation/causation IDs.

### Checkpoint

Contract and transport tests verified; E2E evidence in validation.md.

## Phase 10 — Idempotency & Distributed Consistency

### Goal

Survive duplicates, concurrent writes and lost acknowledgements.

### Design Decisions

Customer/key ID, request fingerprint, source-event inbox, immutable receipt and optimistic version.

### Architecture / Flow

Order transaction; pending begin; receipt write; completion transaction.

### Files Created or Modified

ordering-core; fulfillment-core; runtime adapters; docs/operations.md

### Complete Implementation

All retry-safe transitions and failure-window recovery are implemented.

### How It Works

Only one transaction wins; duplicate network delivery remains allowed.

### How to Run

./mvnw -Pintegration verify

### How to Test

Concurrent creation/completion and conflict tests.

### Production Considerations

Keep deduplication records for the full replay horizon.

### Checkpoint

Concurrency and conflict tests verified.

## Phase 11 — Error Handling, Retry & DLQ

### Goal

Bound retries and isolate poison messages.

### Design Decisions

SDK two attempts/4s; SQS visibility 185s, batch 5/window 5s, five receives, 14-day DLQ.

### Architecture / Flow

Record failure → visibility expiry → retry → DLQ.

### Files Created or Modified

runtime handlers/configuration; infrastructure/terraform/messaging.tf; scripts/failure-e2e.py

### Complete Implementation

Partial responses, retry settings, redrive policies and local fault injection implemented.

### How It Works

Successful records are omitted from failure list and acknowledged.

### How to Run

python3 scripts/failure-e2e.py

### How to Test

HandlerTest; RelayTest; AwsRetryTest; failure E2E.

### Production Considerations

Do not multiply application retry loops; preserve IDs during redrive.

### Checkpoint

Unit transport checks verified; deployed fault evidence in validation.md.

## Phase 12 — LocalStack & Docker

### Goal

Provide reproducible local infrastructure and real Lambda execution.

### Design Decisions

Pinned image/provider, loopback ingress, named network, one Terraform topology.

### Architecture / Flow

Host CLI → emulator; Lambda containers → internal emulator endpoint.

### Files Created or Modified

docker-compose.yml; scripts/init-localstack.sh; Makefile

### Complete Implementation

Compose and idempotent Terraform provisioning script are executable.

### How It Works

Build ZIP, reconcile resources, save local outputs for E2E.

### How to Run

docker compose up -d --wait && ./scripts/init-localstack.sh

### How to Test

python3 scripts/e2e.py

### Production Considerations

Local credentials/auth bypass never cross into AWS.

### Checkpoint

Local provisioning evidence in validation.md; provider correction logged.

## Phase 13 — Terraform Infrastructure

### Goal

Represent all deployed resources and security boundaries as code.

### Design Decisions

Distinct roles/functions, versions/aliases, context tables, queues/topics, receipt bucket, alarms.

### Architecture / Flow

Terraform graph → isolated AWS resources.

### Files Created or Modified

infrastructure/terraform

### Complete Implementation

Complete resource definitions and provider lockfile are committed.

### How It Works

Local endpoint overrides reuse resource definitions; production uses default credential chain.

### How to Run

terraform -chdir=infrastructure/terraform init

### How to Test

terraform -chdir=infrastructure/terraform validate

### Production Considerations

Remote state/account isolation required before AWS apply.

### Checkpoint

Validation and local apply evidence in validation.md.

## Phase 14 — Unit Tests

### Goal

Verify business decisions and adapter error boundaries quickly.

### Design Decisions

JUnit 5 with mocked business ports; HTTP fault server for SDK retries.

### Architecture / Flow

Inputs/failures → use cases/handlers → asserted outcomes.

### Files Created or Modified

ordering-core/src/test; fulfillment-core/src/test; runtime/src/test

### Complete Implementation

Named unit tests cover invalid input, replay, ownership, conflicts and partial batches.

### How It Works

No Spring context or AWS emulator required for core tests.

### How to Run

./mvnw test

### How to Test

Inspect target/surefire-reports.

### Production Considerations

Unit tests do not certify IAM or service semantics.

### Checkpoint

Executed test counts recorded in validation.md.

## Phase 15 — Integration Tests

### Goal

Check real adapter semantics against locally emulated services.

### Design Decisions

Testcontainers isolated LocalStack with DynamoDB/SNS/SQS/S3.

### Architecture / Flow

Create resources → call concrete adapters → inspect durable state.

### Files Created or Modified

runtime/src/test/java/com/synechisveltiosi/commerce/integration/AwsAdaptersIT.java

### Complete Implementation

Seven service integration tests including concurrency and dependency recovery.

### How It Works

Test-only tables/buckets isolate every case; Docker failure is not skipped.

### How to Run

./mvnw -Pintegration verify

### How to Test

Inspect runtime/target/failsafe-reports.

### Production Considerations

Emulator success is not proof of production authorization or throughput.

### Checkpoint

Seven integration tests executed successfully.

## Phase 16 — End-to-End & Failure Tests

### Goal

Exercise deployed functions and actual queue delivery.

### Design Decisions

Bounded polling, state assertions, poison injection through an isolated local fault queue with cleanup.

### Architecture / Flow

POST → Lambda → outbox → SNS/SQS → worker → S3/DB → completed event.

### Files Created or Modified

scripts/e2e.py; scripts/failure-e2e.py; docs/testing.md

### Complete Implementation

Executable workflows validate storage, completion metadata, duplicate invocation and DLQ.

### How It Works

Queries inspect database/object state beyond HTTP acceptance.

### How to Run

python3 scripts/e2e.py && python3 scripts/failure-e2e.py

### How to Test

Evidence files under .local/*result.json.

### Production Considerations

Run production-like signed/load/failure drills in staging separately.

### Checkpoint

Actual execution status recorded in validation.md.

## Phase 17 — Security

### Goal

Define authenticated ownership and least-privilege integrations.

### Design Decisions

AWS_IAM ingress; principal ownership; encrypted private storage; no real secrets.

### Architecture / Flow

Authenticated principal → owner-scoped use case; execution role → scoped AWS resource.

### Files Created or Modified

infrastructure/terraform; platform/Api.java; docs/operations.md

### Complete Implementation

IAM policies, TLS bucket policy, encryption, validation and auth gates are implemented.

### How It Works

Only local mode bypasses API authentication; production endpoint overrides are rejected.

### How to Run

terraform -chdir=infrastructure/terraform plan -var=environment=dev

### How to Test

Handler/domain authorization tests; AWS staging negative tests.

### Production Considerations

LocalStack does not validate effective IAM, KMS or abuse controls.

### Checkpoint

Static/security tests passed; real AWS enforcement NOT VERIFIED.

## Phase 18 — Observability

### Goal

Expose business and infrastructure failure signals without sensitive payloads.

### Design Decisions

Structured JSON/EMF, stable dimensions, CloudWatch dashboard/alarms, active platform X-Ray in AWS.

### Architecture / Flow

Handler/relay metrics → logs/CloudWatch → operator action.

### Files Created or Modified

runtime/src/main/java/com/synechisveltiosi/commerce/platform/Telemetry.java; infrastructure/terraform/observability.tf

### Complete Implementation

Metric emission and infrastructure alarm definitions implemented.

### How It Works

Correlate by business ID; operational counts are not financial ledgers.

### How to Run

Read docs/operations.md monitoring section.

### How to Test

Inspect Lambda logs and alarms; verify notifications in staging.

### Production Considerations

End-to-end OpenTelemetry propagation is documented, not implemented.

### Checkpoint

Local log/resource status recorded; AWS tracing/notification NOT VERIFIED.

## Phase 19 — Production Deployment & Operations

### Goal

Provide controlled promotion, rollback and recovery procedures.

### Design Decisions

Separate accounts/state, immutable artifact promotion, live aliases, protected CI/OIDC deployment.

### Architecture / Flow

Local → AWS dev → staging gates → reviewed production apply.

### Files Created or Modified

docs/operations.md; .github/workflows/verify.yml

### Complete Implementation

CI verification workflow and deployment/incident runbooks are complete.

### How It Works

Promote one artifact; review plan; observe alarms; reconcile rollback with IaC.

### How to Run

Follow docs/operations.md Production deployment.

### How to Test

Staging IAM, restore, load, alerts and rollback drills.

### Production Considerations

No real AWS deployment performed; CI workflow not remotely executed.

### Checkpoint

Production/CI execution NOT VERIFIED with exact actions in validation.md.

## Phase 20 — Final Architecture and Repository Validation

### Goal

Report evidence without overstating readiness.

### Design Decisions

PASS only for executed or definitively inspected checks.

### Architecture / Flow

Source/build/test/IaC/E2E evidence → validation matrix.

### Files Created or Modified

docs/validation.md; README.md; docs/interview.md

### Complete Implementation

Final status matrix and operational limitations summarize the actual repository.

### How It Works

Each missing verification has a reason and exact next action.

### How to Run

Follow README.md quick start.

### How to Test

Run the command matrix in docs/validation.md.

### Production Considerations

Production readiness requires staging evidence beyond emulator checks.

### Checkpoint

See final validation matrix for authoritative results.
