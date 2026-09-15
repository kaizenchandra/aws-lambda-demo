# Operations, security and production transition

## Run locally

Prerequisites: JDK 21, Docker with a working daemon/socket, Maven wrapper download access, Python 3, AWS CLI v2,
Terraform 1.9–1.x. The verified baseline uses Terraform 1.13.5, AWS provider 6.0.0 (committed lockfile), LocalStack
4.12.0. This historical emulator pin avoids a token requirement; upgrades need regression testing. Current LocalStack
images require `LOCALSTACK_AUTH_TOKEN`; never commit it. Configure `LOCALSTACK_IMAGE` consistently for Compose and
Testcontainers when upgrading.

```bash
# macOS when JAVA_HOME is invalid
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B clean verify
# OrbStack only: export DOCKER_SOCK="$HOME/.orbstack/run/docker.sock"
docker compose up -d --wait
./scripts/init-localstack.sh
./mvnw -B -Pintegration verify
python3 scripts/e2e.py
python3 scripts/failure-e2e.py
```

Testcontainers discovers Docker independently from Compose. If discovery fails on OrbStack:

```bash
export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw -B -Pintegration verify
```

Provisioning reconciles the same Terraform local state under `.local/terraform.tfstate`; rerunning must not create
duplicates. Lambda changes publish a new immutable version and move `live`. No production credentials are used locally.
Production default tags are omitted locally because the pinned emulator loses CloudWatch alarm tags on reads. The SDK
region comes from Lambda `AWS_REGION`; container-internal endpoint is `http://localstack:4566`, host endpoint is
`http://localhost:4566`. Production rejects any explicit endpoint override. Default credential provider resolves
execution-role credentials.

Local resources are ephemeral if the Compose container is recreated. If intentionally starting fresh, stop the project
with `docker compose down`, archive/remove `.local/terraform.tfstate*`, then start/provision again. Do not remove state
for existing AWS resources. Do not run production and local applies against the same state.

## API and trust boundaries

Production API uses API Gateway REST AWS_IAM authorization. B2B clients sign execute-api requests with temporary AWS
credentials and have explicitly scoped `execute-api:Invoke` grants for these routes/stage. A 401/403 before Lambda is
expected for unsigned requests. The application derives ownership from API Gateway's authenticated `userArn`; it never
accepts a customer ID in JSON. This baseline scopes ownership to the exact principal ARN, including role-session
identity: clients must use a stable role session name for consistent access. For multi-user SaaS, replace this boundary
with a JWT authorizer and stable issuer+subject or tenant claim; migrate existing owner keys explicitly. Do not share
one principal between unrelated customers.

Local mode uses a fixed customer and unsigned requests, gated by both `APP_ENV=local` and Terraform's local-environment
precondition. Port 4566 binds to loopback. The emulator and Docker socket are trusted developer infrastructure, never
internet-facing.

Create body is `{"items":[{"sku":"JAVA-GUIDE","quantity":2}]}` with `Idempotency-Key` (8–128
alphanumeric/underscore/hyphen). Request body ≤32KiB, 1–20 distinct SKUs, quantity 1–100, server-defined prices and USD.
Unknown JSON fields are ignored for compatibility; a client price is never used. Duplicate fields are rejected by the
parser. Unknown SKU and malformed bodies return 400; same key/different request returns 409; unavailable dependencies
return 503 and instruct same-key retry. POST returns 202 even on an identical replay. No listing endpoints exist.

GET `/orders/{id}` and `/fulfillments/{id}` return 404 for missing or non-owned records. Fulfillment can temporarily be
absent. Completion is owned by fulfillment; an accepted order never pretends to be a paid/shipped order. Receipt keys
are private object identifiers, not public URLs. A future download API must authorize the owner before issuing
short-lived presigned links.

Each function has a separate role: order API writes only Ordering; read API only reads Fulfillment; worker reads/writes
Fulfillment, consumes its queue and writes/heads the receipts prefix; relays query/update only their own table/index and
publish only their topic. SNS queue policies restrict source topic and account. Transaction permissions use DynamoDB's
underlying PutItem/UpdateItem actions. No application permission has Action `*`. Resource `*` is limited to X-Ray
telemetry and DescribeLogGroups APIs without resource-level support. S3's `s3:*` is an explicit TLS-deny bucket policy,
not an allow grant.

DynamoDB uses encryption and production PITR/deletion protection; SQS uses SSE-SQS; SNS uses a rotating customer-managed
KMS key; S3 uses SSE-S3, versioning, public-access blocking and production TLS enforcement. Service endpoints use HTTPS
in AWS. Higher assurance deployments can use separate CMKs for each table/bucket/log group and VPC endpoints after
measuring NAT/cold-start costs. The implementation has no external secrets. When adding a payment provider, keep
credentials in Secrets Manager, scope GetSecretValue to one secret, cache with rotation bounds, and use provider
idempotency keys. Never put secrets in Terraform variables that land in state or log request bodies.

API throttles are 25 requests/s and burst 50; validate these with downstream capacity. Add WAF to the regional stage for
public exposure, with tested rate-based and managed rules. The B2B IAM API is authenticated, but IAM does not remove the
need for abuse controls or customer quotas. API Gateway's account-level log role is a regional singleton: import or
reuse a centrally managed role when one already exists; do not let multiple stacks compete for it.

## Lambda lifecycle and capacity

The Java runtime loads the handler class, which lazily starts one non-web Spring context per execution environment.
Explicit conditional wiring creates only the selected service. Singleton SDK clients reuse connections and credentials
across warm invocations. Do not store invocation state in statics. AWS may discard an environment at any time;
correctness depends on durable state, not reuse. Shutdown hooks cannot be relied upon for delivery.

Cold starts include runtime/JVM startup, class loading, Spring wiring, SDK/client initialization, and first network/TLS
calls. Warm calls avoid most initialization. This reference uses ZIP + managed java21 ARM64, 1024MiB, 30s API/worker and
60s relay timeouts. Memory also affects available CPU; increased memory can reduce latency and sometimes cost, but must
be measured. No latency benchmark is claimed.

Reserved concurrency caps a function's total executions and reserves capacity for it. Worker mapping max concurrency is
5, matching reserved concurrency. API functions reserve 10 and 5; relays reserve 1 each. These require sufficient
account concurrency quota, including AWS's unreserved pool. Increase together with DynamoDB and external capacity.
Provisioned concurrency preinitializes environments for aliases and has an idle cost; enable on the API if measured
cold-tail latency justifies it. It does not remove all scaling cold starts. SnapStart/checkpointing and native images
require separate support/uniqueness/credential testing; not enabled in this reference.

SQS standard queues absorb spikes; ordering of delivery is not guaranteed. Five-record batches with a five-second window
amortize overhead but add latency and make deadline management harder. Worker timeout 30s and visibility 185s follows
six times timeout plus batching window. Each record's failure ID is returned, and `ReportBatchItemFailures` is enabled.
Fatal runtime termination retries the remaining batch; all side effects remain idempotent. Five receives lead to a
processing DLQ; main retention 4 days, DLQ retention 14 days. Poison messages stay isolated for operator correction.
SQS/Lambda backoff and throttles reduce consumption; partial-response mappings can keep polling, so maximum concurrency
is essential for downstream protection.

SDK calls have 4s total timeout, 2s attempt timeout, up to two attempts with the SDK's standard exponential
backoff/jitter. Application services do not loop retries. SQS owns message retries. The relay is scheduled every minute;
it publishes then marks delivered, and another schedule resumes unfinished work. Lambda asynchronous retries are
explicitly disabled for relay aliases, with a 60s event-age limit; the payload is only a wakeup and durable outbox rows
survive a dropped invocation. EventBridge delivery retries cover invocation delivery failure, while the next schedule
owns execution recovery. Each relay queries four shards, 20 entries per shard, and stops near its deadline. The GSI is
eventual; a stale entry may publish twice. At 10× traffic increase pages/relay frequency/concurrency after load testing;
at 100× replace minute polling with DynamoDB-stream-assisted outbox wakeups plus a durable reconciliation sweep, and
review shard count and hot tenant keys. Do not assume this baseline can drain an unbounded backlog. A full page of
permanent failures can starve newer entries in that shard: alarm, repair/quarantine with audit, then replay; an
automated leasing/quarantine scheduler is a deliberate future scaling feature.

## Failure windows and recovery

| Window                                         | Durable state                                           | Retry behavior                                                       |
|------------------------------------------------|---------------------------------------------------------|----------------------------------------------------------------------|
| Order transaction fails                        | Neither aggregate nor outbox, or both if reply was lost | Same key rereads deterministic ID; matching request returns original |
| Order committed, SNS unavailable               | Aggregate + pending outbox                              | Next scheduled relay retries; API remains available                  |
| SNS accepted, mark-delivered fails             | Event may already be queued; pending outbox             | Republish same event ID; consumer deduplicates                       |
| Pending fulfillment stored, S3 unavailable     | PENDING aggregate, no completion outbox                 | SQS retries receipt creation                                         |
| S3 written, completion transaction fails       | Immutable receipt and PENDING aggregate                 | Conditional put detects same digest, transaction retries             |
| Completion committed, acknowledgement lost     | COMPLETED + completion outbox + receipt                 | Consumer returns duplicate success; no new outbox                    |
| Conflicting source event/receipt               | Original state retained                                 | Record fails and eventually DLQs for investigation                   |
| Notification/completion subscriber unavailable | Completion SNS delivery retries; then delivery DLQ      | Restore permissions/consumer and replay with same event ID           |
| Schedule cannot invoke                         | Outbox retained; delivery may enter schedule DLQ        | Next schedule/reconciliation recovers; inspect schedule alarm        |

The pending aggregate acts as an inbox: source event ID + content fingerprint + version. IDs are retained with business
records, without TTL. Replaying after deleting an aggregate can recreate work; retention must cover the replay horizon.
The completion transaction sets version 1 only from version 0 and inserts one outgoing event. Monetary arithmetic is
BigDecimal and never double. Cross-context reads are eventual; within a table transactions are atomic and conflict
resolution reads are strongly consistent. There is no global transaction, exactly-once network delivery or saga in this
bounded business flow.

## Monitoring and incident response

Custom JSON/CloudWatch EMF logs contain timestamp, severity, service, function, AWS request ID, trace ID when available,
correlation/aggregate/event IDs, duration and error class. Avoid exception messages because SDK/provider messages can
contain sensitive input. AWS service error details belong in controlled operator diagnostics. No principal, tokens,
order payload or receipt contents are logged by application handlers.

Dashboard: accepted API requests, completed operations, duplicate events, processing failures, queue depth/age and
function latency. `OrdersAcceptedRequests` counts successful API calls including replays, not unique commercial orders.
`OrdersProcessed` is emitted after a successful commit; a termination before the log can undercount. Metrics are
operational signals, never a financial ledger. Reconcile authoritative business counts from durable data/export
pipelines. OutboxAge identifies delayed delivery. Alarms cover Errors, Duration, Throttles, ConcurrentExecutions, queue
backlog/age, every DLQ, processing/publication/API failures, and outbox delay. Set `alarm_actions` to an operated alert
topic in each AWS environment; an alarm without an action is visible but does not page anyone. Tune static thresholds
after baseline measurement; relay duration has a conservative 25s alarm despite its 60s timeout.

Lambda active X-Ray tracing and API tracing are enabled in AWS, with trace writer IAM. This provides platform traces;
automatic SDK subsegments and event-spanning distributed traces are NOT implemented. To extend them, instrument SDK
clients with ADOT/OpenTelemetry, propagate W3C trace context in the event envelope, and connect asynchronous spans with
links. Correlation IDs already connect business logs across contexts. LocalStack does not prove X-Ray sampling, EMF
ingestion, alert delivery or IAM enforcement.

DLQ runbook: inspect message metadata and error classes; determine invalid contract vs transient dependency; correct
producer/schema/permissions; test one message in staging; use a bounded SQS redrive (or an audited replay tool for SNS
delivery DLQs); watch queue age, errors and duplicates; never purge to conceal failures. Preserve original event ID. For
outbox failures inspect pending shards and topic/KMS IAM, repair delivery, invoke the relay alias or wait for schedule.
Never mark a row delivered before verifying publication. Restore table via PITR into a new table and validate
application references before cutover. Backups and replay need periodic drills.

## Production deployment

Use separate AWS accounts for development, staging and production; separate roles, state keys, KMS keys, quotas, audit
trails and budgets. LocalStack validates behavior, not account controls. Promote the identical tested ZIP; verify its
SHA-256. CI builds and tests, scans dependencies, packages artifacts and runs `terraform validate`; deployment uses
short-lived OIDC credentials and an environment-protected plan/apply job. No long-lived AWS keys in CI.

Terraform's default local state is for local development only. Before an AWS deployment, initialize an encrypted S3
remote backend in a separate bootstrap stack with versioning, TLS, public-access blocking and native lockfile support
(`use_lockfile=true`). Add a `backend "s3" {}` block to versions.tf, and pass the account-specific bucket/key/region
through `terraform init -backend-config`. Terraform state contains infrastructure and configuration metadata: restrict
access and retain history. Do not migrate `.local/terraform.tfstate` into an AWS backend.

```bash
./mvnw -B -Pintegration clean verify
terraform -chdir=infrastructure/terraform init
terraform -chdir=infrastructure/terraform validate
terraform -chdir=infrastructure/terraform plan -var='environment=dev' -out=dev.tfplan
# Review the exact account, state backend, IAM, costs and artifact hash, then:
terraform -chdir=infrastructure/terraform apply dev.tfplan
```

Production permissions/cost changes are not executed by this repository's local scripts. The reference publishes
versions and routes triggers through `live` aliases. For canaries use CodeDeploy or weighted aliases with alarms; update
Terraform so it owns the intended weights. Rollback aliases to the last known-good version, then reconcile IaC. Avoid
automatic destructive schema changes: old and new consumers must coexist during deployment. A Lambda rollback cannot
undo committed business data; events must remain backward compatible.

Run staging checks for signed authentication/authorization, IAM negative cases, KMS denial, load/throttling, forced
termination, SNS delivery failure, alarm notifications, outbox starvation, backup restore and DLQ redrive. Test
cancellation/compensation only when corresponding business capabilities are added. Review costs: provisioned
concurrency, memory-duration, DynamoDB transactional writes, GSI amplification, SNS/SQS calls, S3 retention, KMS and
CloudWatch log/cardinality costs. Set AWS Budgets, retention and concurrency caps; avoid event IDs as metric dimensions.

## Performance and platform alternatives

| Factor             | Optimization                                             | Trade-off                                                        |
|--------------------|----------------------------------------------------------|------------------------------------------------------------------|
| JVM/Spring startup | Narrow explicit wiring; no web container                 | More explicit configuration                                      |
| SDK initialization | Reuse synchronous clients and URL-connection transport   | Blocking calls; concurrency occurs across Lambda environments    |
| Serialization      | Small typed records, one configured mapper               | Shared contract evolution discipline                             |
| Memory/CPU         | Benchmark 512/1024/2048MiB with representative payloads  | Higher memory price can offset faster CPU; measure               |
| Network calls      | Keep SDK clients out of invocation; same-region services | Durable transaction/receipt calls cannot be removed casually     |
| Batch size         | Tune 1–10 with timeout budget                            | Throughput vs latency and failure blast radius                   |
| Concurrency        | Mapping limit + reserved concurrency                     | Protects dependencies but increases backlog                      |
| DynamoDB           | On-demand capacity + spread keys and outbox shards       | Burst limits/hot partitions still matter; transactions cost more |
| Cold-tail latency  | Provisioned concurrency for API alias                    | Idle charges and deployment warmup                               |

Lambda fits bursty, event-driven work with short bounded execution and independent scaling. Sustained predictable
traffic may be cheaper on long-running Spring Boot services in ECS/Fargate. Connection-heavy protocols or strict
low-tail latency often favor always-on services. Long or CPU-intensive jobs should use batch workers, ECS or EC2 sized
for the workload. EKS helps organizations already operating Kubernetes or needing its scheduling/platform ecosystem; it
adds substantial operational complexity. EC2 provides maximum control with patching/capacity responsibility. Measure
cost, latency, duration and team operating capacity before migrating. Preserve the core ports/domain when replacing
Lambda adapters with HTTP/message workers.
