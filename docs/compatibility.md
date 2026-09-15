# Dependency and runtime compatibility

Verified against authoritative documentation on 2026-09-15; execution evidence is recorded in validation.md.

| Component              | Pin                              | Rationale                                                                                            |
|------------------------|----------------------------------|------------------------------------------------------------------------------------------------------|
| Java                   | release 21 / Lambda java21 ARM64 | Managed runtime and locally installed JDK 21                                                         |
| Spring Boot            | 4.1.1                            | Existing project version; official system requirements include Java 21                               |
| AWS SDK                | BOM 2.54.7                       | One version for all SDK modules; Java 21 compatible                                                  |
| Lambda core/events     | 1.2.3 / 3.16.1                   | Direct handler and API Gateway/SQS event types                                                       |
| Jackson 2              | databind/jsr310 2.20.1           | Explicit transport serialization without Boot MVC; no Spring Cloud Function compatibility dependency |
| JUnit Jupiter          | 5.13.4 BOM                       | Explicit JUnit 5 requirement; separate `junit5.version` avoids overriding Boot's JUnit 4 property    |
| Mockito                | 5.20.0                           | Business-port unit tests                                                                             |
| Testcontainers         | 1.21.4 BOM                       | JUnit 5 integration and LocalStack API                                                               |
| LocalStack             | 4.12.0                           | Cached, reproducible historical no-token baseline; run upgrades through tests                        |
| Terraform              | tested 1.13.5                    | Supports required provider and lifecycle expressions                                                 |
| AWS Terraform provider | 6.0.0 lockfile                   | Paired with LocalStack baseline after newer-provider incompatibility                                 |

Sources:

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html): Boot 4.1.1 requires
  Java 17+ and supports through Java 26, with Maven 3.6.3+.
- [AWS Java Lambda runtimes](https://docs.aws.amazon.com/lambda/latest/dg/lambda-java.html): managed Java 21 on Amazon
  Linux 2023.
- [AWS SDK setup](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup.html): SDK 2.x Java
  prerequisites and credential configuration.
- [AWS SDK API reference](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/lambda/model/GetEventSourceMappingResponse.html):
  current SDK family reference; Maven build verifies selected artifacts.
- [Testcontainers LocalStack module](https://java.testcontainers.org/modules/localstack/): container endpoint, region
  and credentials pattern.
- [LocalStack image versions/authentication](https://docs.localstack.cloud/aws/customization/other-installations/docker-images/):
  current calendar-versioned images require a token; historical pins must be deliberate.
- [SQS Lambda configuration](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html): visibility
  timeout and consumer configuration.
- [Partial batch handling](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html): at-least-once behavior and
  partial response requirement.
- [Terraform Lambda resource](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function):
  managed function configuration; provider validation is the executable schema check.

Boot 4 defaults do not force this non-web application to use its default HTTP/JSON stack. Jackson is an adapter detail.
No Boot test starter is used because the requested test framework is JUnit 5 and no Spring context is needed for
business tests. The assembled ZIP contains ordinary classes plus dependency JARs in lib/, not an executable Boot nested
JAR. Framework compatibility is supported by the build and actual Lambda emulator invocation, not documentation alone.
