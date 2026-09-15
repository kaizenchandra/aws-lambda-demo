# Final validation ledger

Validated on **2026-09-15**. PASS means an executed check or a definite source/configuration inspection, as specified in the evidence column. It does not mean AWS production certification.

## Environment and artifact

- JDK 21.0.12.1, Java release target 21; Spring Boot 4.1.1; AWS SDK 2.54.7.
- Terraform 1.13.5 / AWS provider 6.0.0; LocalStack 4.12.0; Docker Engine 29.4.0 on macOS ARM64.
- Four Maven modules: contracts, ordering-core, fulfillment-core, runtime.
- **35 unit tests + 7 Testcontainers integration tests = 42 tests; zero failures, errors or skips.** See [machine-readable test summary](evidence/tests.json).
- Deployment artifact: `runtime/target/commerce.zip`; SHA-256 `4e4527f3b2b4bf51f4c98d7742f5c08a4e0f77d583395ef94692053250feef21`.
- ZIP contains application classes/resources at the root and 85 dependency JARs under `lib/`; no Boot nested `BOOT-INF` layout.

## Executed and inspected checks

| Validation item | Status | Evidence and scope |
|---|---|---|
| DDD boundaries and aggregate ownership | PASS | Separate Order/Fulfillment roots, tables, repositories and vocabulary inspected; no cross-context repository/model imports |
| Clean Architecture dependency direction | PASS | `scripts/check-architecture.py`; compiled `jdeps` output shows each core depends only on java.base and contracts |
| Inbound/outbound ports and adapters | PASS | Constructor-injected use cases/repositories/receipt port; unit port tests and concrete adapter integration tests |
| Java packages, imports, Maven modules/dependencies | PASS | Clean Java 21 reactor compilation and verification; all POMs parse |
| Domain invariants and monetary arithmetic | PASS | JUnit tests for invalid items, totals, quantities, ownership and BigDecimal normalization |
| Spring Boot and reusable AWS configuration | PASS | Actual Lambda emulator invocations boot the static context and reuse clients; endpoint overrides are local-gated in source |
| Lambda packaging and handlers | PASS | ZIP inspection plus actual API/SQS handler execution in LocalStack |
| API Gateway and synchronous workflow | PASS | Deployed POST/GET, 400 invalid input, 409 key conflict, 202 replay, stored state assertions |
| DynamoDB persistence/conditional writes | PASS | Real emulated PutItem/GetItem/UpdateItem/transactions; concurrency, stale-version and outbox tests |
| SNS/SQS communication | PASS | Adapter tests and deployed normal event flow; completion envelope observed in subscriber queue |
| S3 immutable receipt | PASS | Real conditional writes, conflicting-content test, completed receipt content checked by E2E |
| Idempotency and concurrency | PASS | Concurrent create/completion tests; deployed duplicate invocation and concurrent API replay |
| SDK transient/persistent failures | PASS | Controlled HTTP 500→200 and persistent-500 tests verify exactly two bounded SDK attempts |
| Database/publish failure windows | PASS | Port fault tests plus real missing-topic recovery; pending outbox retained |
| Downstream failure recovery | PASS | Real missing-bucket failure leaves PENDING; retry creates receipt and completion |
| Partial SQS batch handling | PASS | Unit mixed batch and deployed mixed-batch invocation return only the failed ID |
| Retry and DLQ mechanism | PASS | Dedicated local fault queue mapped to the same deployed worker redrives poison; DLQ receive count 3; healthy main workflow completes. This uses 30s/two-receive test settings, not a full 185s/five-receive production-duration drill |
| Fault-test cleanup/main queue settings | PASS | Temporary queue/mapping/IAM policy removed; main queue remains 185s visibility and five receives |
| IAM/security implementation | PASS | Scoped role/policy source inspection, local owner/unauthenticated unit tests, encryption/private-bucket resources created. Effective AWS enforcement is separate below |
| Docker Compose / Testcontainers | PASS | Project Compose emulator and independent Testcontainers emulators ran; tests do not skip Docker failures |
| Terraform syntax/provider schema | PASS | `terraform validate` and `terraform fmt -check` with pinned provider |
| Local Terraform deployment | PASS | Initial 106 resources plus two relay async-invocation configurations; final repository provisioning script executed successfully |
| Repeated provisioning convergence | PASS | Executed `scripts/init-localstack.sh`; subsequent detailed-exitcode Terraform plan reports No changes and exits 0 |
| Structured logs / EMF document shape | PASS | Actual fulfillment CloudWatch log records contain expected fields and EMF metadata |
| CloudWatch alarm/dashboard resources | PASS | Local Terraform created Lambda, queue, DLQ, business and outbox alarms plus dashboard; this does not verify AWS alarm notification or EMF ingestion |
| Documentation/contracts/scripts | PASS | Phase guide, Mermaid sources, 11 ADRs, API/event JSON and operational runbooks inspected; Python/XML/JSON syntax checks executed. All repository-relative Markdown links were checked |

## Evidence files

- [Artifact and Terraform deployment evidence](evidence/deployment.json)
- [JUnit/Testcontainers summary](evidence/tests.json)
- [Deployed E2E result](evidence/e2e-result.json)
- [Deployed retry/DLQ result](evidence/failure-e2e-result.json)

The E2E result includes persisted fulfillment state, receipt key, completion event ID, duplicate invocation, concurrent API replay and real partial-batch result. The fault test uses isolated temporary queues and a narrowly scoped temporary IAM policy. It never weakens the application's main queue settings.

## Exact repeat commands

From repository root, with Java 21, Terraform, AWS CLI and Docker available:

```bash
./mvnw -B -Pintegration clean verify
python3 scripts/check-architecture.py
terraform -chdir=infrastructure/terraform init -input=false
terraform -chdir=infrastructure/terraform validate
terraform -chdir=infrastructure/terraform fmt -check
docker compose up -d --wait
./scripts/init-localstack.sh
python3 scripts/e2e.py
python3 scripts/failure-e2e.py
terraform -chdir=infrastructure/terraform plan -input=false -detailed-exitcode \
  -var='local_mode=true' -var='environment=local' -state=../../.local/terraform.tfstate
```

Expected: tests report zero failures/skips; E2E/fault scripts exit 0; Terraform's final plan exits 0 with no changes. A detailed-exitcode value of 2 means drift and must be investigated, not called a no-op.

On this workstation `JAVA_HOME` was initially invalid and Terraform was absent. Verification used these environment overrides rather than changing global machine configuration:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH=/tmp/commerce-bin:$PATH
export MAVEN_OPTS=-Dmaven.repo.local=/tmp/commerce-m2
export DOCKER_SOCK="$HOME/.orbstack/run/docker.sock"
export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Terraform was downloaded from HashiCorp into `/tmp/commerce-bin`. Maven downloads used `/tmp/commerce-m2`. These paths are temporary workstation accommodations, not required repository layout. Network/Docker/provider IPC checks required sandbox escalation; those actions were approved and executed.

## NOT VERIFIED: required AWS/CI gates

No real AWS account deployment or remote CI execution was performed. Do not mark these PASS from LocalStack results.

| Item | Reason | Exact verification command/action | Expected result |
|---|---|---|---|
| AWS development/staging/production deployment | No target AWS account was selected or deployed | In the authorized isolated dev account, initialize a separate encrypted remote state as described in operations.md; run `terraform -chdir=infrastructure/terraform plan -var='environment=dev' -out=dev.tfplan`, review it, then `terraform -chdir=infrastructure/terraform apply dev.tfplan` | Correct-account resources become healthy; production endpoint overrides/local bypass absent |
| Real API authentication/authorization | Emulator/unit tests cannot certify API Gateway IAM enforcement | Against the deployed AWS API, send the same create/read request using two distinct SigV4-authenticated customer principals; also send `curl -i "$AWS_API_URL/orders"` without a signature | Unsigned request rejected before Lambda; non-owner GET returns 404; owner create/read succeeds |
| Effective least-privilege IAM and KMS | LocalStack does not prove authorization decisions | Run IAM policy simulation and live negative tests for each function role: order role reading Fulfillment, worker reading Ordering, relay publishing other topic, worker deleting receipt; test SNS publication with an explicit KMS deny in staging | Forbidden operations denied; allowed workflow succeeds; KMS denial leaves pending outbox recoverable |
| Alarm notifications and business-metric ingestion | Resources/log shape checked; no real CloudWatch subscription was exercised | Configure staging `alarm_actions`; trigger one worker failure and poison DLQ record, then inspect `aws cloudwatch describe-alarms --alarm-name-prefix commerce-staging` and the operated notification endpoint | Expected alarm transitions and delivered notification; EMF counters visible in Commerce namespace |
| X-Ray/OpenTelemetry | Platform tracing configuration exists; cross-event tracing instrumentation is not implemented | Invoke signed AWS request and inspect X-Ray trace; add ADOT SDK instrumentation plus event trace context/linking before claiming end-to-end distributed traces | Platform trace visible; instrumented SDK/asynchronous spans linked only after that extension is implemented |
| Cold/warm performance and 10×/100× load | No representative AWS benchmark executed | Run a staging load test at 25 requests/s, then controlled 10×/100× offered load while observing throttles, p95/p99 latency, concurrency, outbox age and queue age; repeat across memory settings | Measured limits and cost/SLO decisions; no lost accepted orders or duplicate transitions |
| Real throttling, timeout termination and five-receive DLQ timing | Tests cover HTTP transport failure and accelerated local redrive, not AWS scheduling | In staging restrict dependency throughput/permissions, force a worker timeout, restore access, and observe the configured 185s/five-receive path | Safe replay, bounded resource use, eventual recovery or DLQ with durable state preserved |
| Restore and rollback drills | PITR, aliases and runbooks are defined but drills not executed | Run `aws dynamodb restore-table-to-point-in-time --source-table-name commerce-staging-ordering --target-table-name commerce-staging-ordering-restore --use-latest-restorable-time`; validate records/outboxes. Select the prior published version from `aws lambda list-versions-by-function --function-name commerce-staging-order-api`, then run `aws lambda update-alias --function-name commerce-staging-order-api --name live --function-version "$PREVIOUS_VERSION"` and reconcile Terraform | Restored data recoverable; prior artifact serves requests; incompatible event/schema changes are caught |
| Remote CI workflow | Workspace is not a Git repository; workflow has not run on GitHub | Commit/push into the intended GitHub repository and run the `Verify commerce` workflow | Unit/integration/E2E/failure/format gates green; reports and ZIP uploaded |

## Corrected issues discovered during execution

- JUnit property collision: renamed the Jupiter version property so Boot's inherited JUnit 4 dependency was not assigned a Jupiter version.
- DynamoDB reserved word: aliased the outbox `sequence` attribute in the update expression; publication/recovery tests pass.
- Provider/emulator mismatch: pinned AWS provider 6.0.0 after 6.64.0 caused LocalStack CloudWatch response errors. Archived failed task-local state and recreated only task-owned emulator resources because provider state could not be downgraded.
- Local tag drift: omit default tags locally and retain explicit API Gateway tags to accommodate emulator tagging behavior; production default tags remain enabled.
- Monetary JSON normalization: normalize accepted monetary values to scale two before generating the deterministic receipt fingerprint. No production data was migrated; only pre-release local fixtures existed.
- Fault acceleration: replaced mutation of an already-polled queue with a dedicated preconfigured fault queue, mapping and scoped policy; cleanup is checked.
- Request-size semantics: measure UTF-8 bytes rather than Java character count; multibyte regression test passes.

There are no unresolved application test failures in the executed suite. Production operational gates above remain intentionally unverified until exercised in AWS.
