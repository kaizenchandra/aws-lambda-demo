package com.synechisveltiosi.commerce.platform.outbox;

import static com.synechisveltiosi.commerce.platform.Dynamo.*;

import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.platform.Json;
import java.time.*;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class DynamoOutboxStore implements OutboxStore {
  private final DynamoDbClient db;
  private final String table;
  private final Clock clock;

  public DynamoOutboxStore(DynamoDbClient db, String table, Clock clock) {
    this.db = db;
    this.table = table;
    this.clock = clock;
  }

  public static Put insert(String table, IntegrationEvent<?> event) {
    var item = new HashMap<String, AttributeValue>(key("OUTBOX#" + event.eventId()));
    item.put("body", s(Json.write(event)));
    item.put("pending", s("PENDING#" + Math.floorMod(event.eventId().hashCode(), 4)));
    item.put("sequence", s(event.occurredAt() + "#" + event.eventId()));
    item.put("occurredAt", s(event.occurredAt().toString()));
    return Put.builder()
        .tableName(table)
        .item(item)
        .conditionExpression("attribute_not_exists(pk)")
        .build();
  }

  public List<Pending> pending(int shard) {
    return db
        .query(
            r ->
                r.tableName(table)
                    .indexName("pending")
                    .keyConditionExpression("pending = :p")
                    .expressionAttributeValues(Map.of(":p", s("PENDING#" + shard)))
                    .limit(20))
        .items()
        .stream()
        .map(
            i ->
                new Pending(
                    i.get("pk").s(),
                    i.get("body").s(),
                    Math.max(
                        0,
                        Duration.between(Instant.parse(i.get("occurredAt").s()), clock.instant())
                            .toSeconds())))
        .toList();
  }

  public void delivered(String key) {
    try {
      db.updateItem(
          r ->
              r.tableName(table)
                  .key(key(key))
                  .updateExpression("SET expiresAt = :ttl REMOVE pending, #sequence")
                  .expressionAttributeNames(Map.of("#sequence", "sequence"))
                  .conditionExpression("attribute_exists(pending)")
                  .expressionAttributeValues(
                      Map.of(":ttl", n(clock.instant().plusSeconds(14 * 86400).getEpochSecond()))));
    } catch (ConditionalCheckFailedException ignored) {
      /* A concurrent relay already marked this published event. */
    }
  }
}
