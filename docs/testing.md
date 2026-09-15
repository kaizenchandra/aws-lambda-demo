# Testing strategy and failure matrix

`./mvnw clean verify` runs plain JUnit 5/Mockito tests and builds all artifacts. `./mvnw -Pintegration verify` additionally starts a fresh Testcontainers LocalStack and runs adapter tests; Docker absence is a failure, not a skipped green build. `python3 scripts/e2e.py` exercises deployed API Gateway/Lambda/SNS/SQS/DynamoDB/S3, with bounded polling. `python3 scripts/failure-e2e.py` creates a temporary local fault queue and mapping to the same deployed worker, with 30s visibility and two receives, then removes the temporary mapping, scoped IAM grant and queues in a finally block. The main queue retains its production-style 185s/five-receive settings. Do not run fault injection against production.

| Scenario | Test evidence | Expected durable state |
|---|---|---|
| Invalid request | OrderTest and HandlerTest; E2E 400 | No aggregate/outbox created |
| Duplicate delivery | FulfillmentTest, AwsAdaptersIT; deployed duplicate invoke | One version-1 fulfillment, one receipt, one logical completion event |
| Concurrent aggregate processing | AwsAdaptersIT parallel creates/workers; E2E concurrent API replay | One creation/transition winner; conflicts reread winner; transient service conflicts may need caller retry |
| DynamoDB conditional conflict | AwsAdaptersIT.complete_staleVersion_rejectsConditionalWrite | Version remains 1; no second completion outbox |
| Lambda processing failure | HandlerTest per-record failure; failure E2E poison | Failed record returned; healthy messages can complete |
| SQS retry | failure-e2e.py deployed event-source mapping | Receive count increases; no poisoned business aggregate |
| DLQ routing | failure-e2e.py | Matching poison reaches processing DLQ; temporary fault resources removed |
| Partial batch response | HandlerTest and deployed mixed-batch invocation | Only bad message ID returned |
| Downstream AWS failure | AwsAdaptersIT nonexistent S3 bucket, then recovery | PENDING survives; receipt/completion written on retry |
| Event publishing failure | RelayTest; AwsAdaptersIT nonexistent SNS topic | Outbox remains pending and publishes after recovery |
| Publish succeeded, DB ack failed | RelayTest | Same logical event republished, never dropped |
| Transient AWS SDK HTTP failure | AwsRetryTest real local HTTP server returns 500 then 200 | Exactly two SDK requests; no application retry loop |
| Persistent SDK failure | AwsRetryTest server always 500 | Stops after two attempts, propagates failure |
| Conflicting immutable receipt | AwsAdaptersIT | Existing object retained, completion rejected |
| Event schema evolution | HandlerTest unknown optional field/version | Additive fields accepted; unsupported version rejected |
| Cross-customer access | OrderTest owner isolation; handler unauthenticated check | Hidden 404 or rejected request; AWS IAM still needs staging verification |

Integration tests use emulated service APIs rather than mocked SDK clients. Unit tests use mocked outbound business ports. The transient transport test is a controlled HTTP fault server, not a claim of testing actual AWS throttling. Integration test scans count records only in isolated test tables; production request paths never scan. Domain tests need no Spring context, SDK or Docker.

E2E verifies stored order, strong-read fulfillment version/status, S3 content, completion event metadata and duplicate stability. It does not infer success from the initial 202. The duplicate test directly invokes the deployed alias with an SQS batch to avoid racing a background duplicate with a state assertion. The normal path and poison test use the actual queue mapping. Polling has deadlines and useful assertion failures; no arbitrary long sleeps.

Remaining AWS staging tests: unsigned/signed requests, cross-principal ownership, explicit deny IAM, KMS failures, real throttles, Lambda timeout termination, alarm notification, distributed tracing, backup restore, measured cold/warm load and canary rollback. LocalStack cannot certify these controls.
