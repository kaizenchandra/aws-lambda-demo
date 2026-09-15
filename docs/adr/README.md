# Architecture decision records

| ADR               | Decision                                           |
|-------------------|----------------------------------------------------|
| [ADR-001](001.md) | Managed Lambda and direct Java handlers            |
| [ADR-002](002.md) | SQS buffers and SNS distributes integration events |
| [ADR-003](003.md) | Access-pattern-first DynamoDB                      |
| [ADR-004](004.md) | Two business bounded contexts                      |
| [ADR-005](005.md) | Core modules enforce inward dependencies           |
| [ADR-006](006.md) | Transactional outbox and aggregate inbox           |
| [ADR-007](007.md) | Typed versioned integration envelopes              |
| [ADR-008](008.md) | Pinned LocalStack and same Terraform topology      |
| [ADR-009](009.md) | Immutable receipts before completion               |
| [ADR-010](010.md) | IAM authentication for B2B API                     |
| [ADR-011](011.md) | SDK retries bounded; queue owns business retry     |
