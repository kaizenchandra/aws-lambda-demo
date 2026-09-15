package com.synechisveltiosi.commerce.integration;

import static com.synechisveltiosi.commerce.platform.Dynamo.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.adapter.out.*;
import com.synechisveltiosi.commerce.fulfillment.application.FulfillmentService;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;
import com.synechisveltiosi.commerce.ordering.adapter.out.DynamoOrderRepository;
import com.synechisveltiosi.commerce.ordering.application.OrderService;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases.Item;
import com.synechisveltiosi.commerce.ordering.domain.Catalog;
import com.synechisveltiosi.commerce.platform.*;
import com.synechisveltiosi.commerce.platform.outbox.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Testcontainers
class AwsAdaptersIT {
  @Container
  static final LocalStackContainer local =
      new LocalStackContainer(
              DockerImageName.parse(
                  System.getenv().getOrDefault("LOCALSTACK_IMAGE", "localstack/localstack:4.12.0")))
          .withServices(
              LocalStackContainer.Service.DYNAMODB,
              LocalStackContainer.Service.S3,
              LocalStackContainer.Service.SNS,
              LocalStackContainer.Service.SQS);

  static DynamoDbClient db;
  static S3Client s3;
  static SnsClient sns;
  static SqsClient sqs;
  String ordering, fulfillment, bucket;

  @BeforeAll
  static void clients() {
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(local.getAccessKey(), local.getSecretKey()));
    var region = Region.of(local.getRegion());
    db =
        DynamoDbClient.builder()
            .endpointOverride(local.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .build();
    s3 =
        S3Client.builder()
            .endpointOverride(local.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build();
    sns =
        SnsClient.builder()
            .endpointOverride(local.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .build();
    sqs =
        SqsClient.builder()
            .endpointOverride(local.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .build();
  }

  @AfterAll
  static void close() {
    if (db != null) db.close();
    if (s3 != null) s3.close();
    if (sns != null) sns.close();
    if (sqs != null) sqs.close();
  }

  @BeforeEach
  void resources() {
    var suffix = UUID.randomUUID().toString();
    ordering = "orders-" + suffix;
    fulfillment = "fulfillment-" + suffix;
    bucket = "receipts-" + suffix;
    table(ordering);
    table(fulfillment);
    s3.createBucket(r -> r.bucket(bucket));
  }

  void table(String name) {
    db.createTable(
        r ->
            r.tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("pending")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("sequence")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName("pending")
                        .keySchema(
                            KeySchemaElement.builder()
                                .attributeName("pending")
                                .keyType(KeyType.HASH)
                                .build(),
                            KeySchemaElement.builder()
                                .attributeName("sequence")
                                .keyType(KeyType.RANGE)
                                .build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build()));
    db.waiter().waitUntilTableExists(r -> r.tableName(name));
  }

  OrderService orders() {
    return new OrderService(
        new DynamoOrderRepository(db, ordering), new Catalog(), Clock.systemUTC());
  }

  IntegrationEvent<AcceptedOrder> accept() {
    orders().create("customer", "request-" + UUID.randomUUID(), List.of(new Item("JAVA-GUIDE", 2)));
    var store = new DynamoOutboxStore(db, ordering, Clock.systemUTC());
    var pending =
        await(
            () -> {
              for (int i = 0; i < 4; i++) {
                var items = store.pending(i);
                if (!items.isEmpty()) return items.getFirst();
              }
              return null;
            });
    return Json.read(pending.body(), new TypeReference<IntegrationEvent<AcceptedOrder>>() {});
  }

  @Test
  void workflow_realAwsAdapters_persistsReceiptAndPublishesCompletion() {
    var event = accept();
    var topic = sns.createTopic(r -> r.name("accepted-" + UUID.randomUUID())).topicArn();
    var queue = sqs.createQueue(r -> r.queueName("subscriber-" + UUID.randomUUID())).queueUrl();
    var queueArn =
        sqs.getQueueAttributes(r -> r.queueUrl(queue).attributeNames(QueueAttributeName.QUEUE_ARN))
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN);
    sqs.setQueueAttributes(
        r ->
            r.queueUrl(queue)
                .attributes(
                    Map.of(
                        QueueAttributeName.POLICY,
                        Json.write(
                            Map.of(
                                "Version",
                                "2012-10-17",
                                "Statement",
                                List.of(
                                    Map.of(
                                        "Effect",
                                        "Allow",
                                        "Principal",
                                        Map.of("Service", "sns.amazonaws.com"),
                                        "Action",
                                        "sqs:SendMessage",
                                        "Resource",
                                        queueArn,
                                        "Condition",
                                        Map.of("ArnEquals", Map.of("aws:SourceArn", topic)))))))));
    sns.subscribe(
        r ->
            r.topicArn(topic)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
    var relay =
        new OutboxRelay(
            new DynamoOutboxStore(db, ordering, Clock.systemUTC()),
            new SnsEventPublisher(sns, topic));
    assertEquals(
        0,
        relay.run(
            (i, e) -> {
              if (e != null) throw new AssertionError("Relay failed", e);
            },
            () -> true));
    var message =
        await(
            () -> {
              var messages =
                  sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(1)).messages();
              return messages.isEmpty() ? null : messages.getFirst();
            });
    var consumed =
        Json.read(message.body(), new TypeReference<IntegrationEvent<AcceptedOrder>>() {});
    assertEquals(event.eventId(), consumed.eventId());
    var repo = new DynamoFulfillmentRepository(db, fulfillment);
    var worker = new FulfillmentService(repo, new S3ReceiptStorage(s3, bucket));
    assertTrue(worker.process(consumed));
    assertFalse(worker.process(consumed));
    var result = repo.find(event.aggregateId()).orElseThrow();
    assertEquals(Fulfillment.Status.COMPLETED, result.status());
    assertTrue(
        s3.getObjectAsBytes(r -> r.bucket(bucket).key(result.receiptKey()))
            .asUtf8String()
            .contains(event.aggregateId().toString()));
    assertEquals(2, db.scan(r -> r.tableName(fulfillment)).count());
    var completeTopic = sns.createTopic(r -> r.name("completed-" + UUID.randomUUID())).topicArn();
    assertEquals(
        0,
        new OutboxRelay(
                new DynamoOutboxStore(db, fulfillment, Clock.systemUTC()),
                new SnsEventPublisher(sns, completeTopic))
            .run((i, e) -> {}, () -> true));
  }

  @Test
  void createOrder_concurrentRequests_hasOneAggregateAndOutbox() throws Exception {
    var service = orders();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () ->
                service.create("customer", "same-request", List.of(new Item("AWS-GUIDE", 1))).id());
      var ids = new HashSet<UUID>();
      for (var future : executor.invokeAll(tasks)) ids.add(future.get());
      assertEquals(1, ids.size());
    }
    assertEquals(2, db.scan(r -> r.tableName(ordering)).count());
  }

  @Test
  void processOrder_concurrentConsumers_completeOnce() throws Exception {
    var event = accept();
    var repo = new DynamoFulfillmentRepository(db, fulfillment);
    var service = new FulfillmentService(repo, new S3ReceiptStorage(s3, bucket));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var results =
          executor.invokeAll(
              List.of(
                  () -> service.process(event),
                  () -> service.process(event),
                  () -> service.process(event),
                  () -> service.process(event)));
      int winners = 0;
      for (var result : results) if (Boolean.TRUE.equals(result.get())) winners++;
      assertEquals(1, winners);
    }
    assertEquals(1, repo.find(event.aggregateId()).orElseThrow().version());
    assertEquals(2, db.scan(r -> r.tableName(fulfillment)).count());
    assertEquals(1, s3.listObjectsV2(r -> r.bucket(bucket)).keyCount());
  }

  @Test
  void processOrder_s3Unavailable_recoversFromPending() {
    var event = accept();
    var repo = new DynamoFulfillmentRepository(db, fulfillment);
    assertThrows(
        S3Exception.class,
        () ->
            new FulfillmentService(repo, new S3ReceiptStorage(s3, "missing-" + UUID.randomUUID()))
                .process(event));
    assertEquals(Fulfillment.Status.PENDING, repo.find(event.aggregateId()).orElseThrow().status());
    assertEquals(1, db.scan(r -> r.tableName(fulfillment)).count());
    assertTrue(new FulfillmentService(repo, new S3ReceiptStorage(s3, bucket)).process(event));
  }

  @Test
  void relay_snsUnavailable_retainsPendingAndRecovers() {
    accept();
    var store = new DynamoOutboxStore(db, ordering, Clock.systemUTC());
    var bad =
        new OutboxRelay(
            store,
            new SnsEventPublisher(
                sns, "arn:aws:sns:" + local.getRegion() + ":000000000000:missing"));
    assertEquals(1, bad.run((i, e) -> {}, () -> true));
    var topic = sns.createTopic(r -> r.name("recovered-" + UUID.randomUUID())).topicArn();
    assertEquals(
        0, new OutboxRelay(store, new SnsEventPublisher(sns, topic)).run((i, e) -> {}, () -> true));
    await(
        () -> {
          for (int i = 0; i < 4; i++) if (!store.pending(i).isEmpty()) return null;
          return true;
        });
  }

  @Test
  void putReceipt_differentContent_conflicts() {
    var storage = new S3ReceiptStorage(s3, bucket);
    var id = UUID.randomUUID();
    storage.putImmutable(id, "original");
    assertEquals(storage.putImmutable(id, "original"), "receipts/" + id + ".txt");
    assertThrows(IllegalStateException.class, () -> storage.putImmutable(id, "changed"));
  }

  @Test
  void complete_staleVersion_rejectsConditionalWrite() {
    var event = accept();
    var repo = new DynamoFulfillmentRepository(db, fulfillment);
    var service = new FulfillmentService(repo, new S3ReceiptStorage(s3, bucket));
    assertTrue(service.process(event));
    var completed = repo.find(event.aggregateId()).orElseThrow();
    var outgoing =
        new IntegrationEvent<>(
            UUID.randomUUID(),
            "FulfillmentCompleted",
            "1.0",
            event.aggregateId(),
            "c",
            "c",
            Instant.now(),
            new CompletedFulfillment("customer", completed.receiptKey()));
    assertFalse(repo.complete(completed, outgoing));
    assertEquals(2, db.scan(r -> r.tableName(fulfillment)).count());
  }

  static <T> T await(Supplier<T> condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      T value = condition.get();
      if (value != null) return value;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Condition not satisfied within 30 seconds");
  }
}
