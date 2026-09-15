package com.synechisveltiosi.commerce.fulfillment.adapter.out;

import static com.synechisveltiosi.commerce.platform.Dynamo.*;

import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.application.port.out.FulfillmentRepository;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;
import com.synechisveltiosi.commerce.platform.Json;
import com.synechisveltiosi.commerce.platform.outbox.DynamoOutboxStore;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class DynamoFulfillmentRepository implements FulfillmentRepository {
  private final DynamoDbClient db;
  private final String table;

  public DynamoFulfillmentRepository(DynamoDbClient db, String table) {
    this.db = db;
    this.table = table;
  }

  public Optional<Fulfillment> find(UUID id) {
    var item =
        db.getItem(r -> r.tableName(table).key(key("FULFILLMENT#" + id)).consistentRead(true))
            .item();
    return item.isEmpty()
        ? Optional.empty()
        : Optional.of(Json.read(item.get("body").s(), Fulfillment.class));
  }

  public Fulfillment begin(Fulfillment pending) {
    var item = new HashMap<String, AttributeValue>(key("FULFILLMENT#" + pending.orderId()));
    item.put("body", s(Json.write(pending)));
    item.put("version", n(0));
    try {
      db.putItem(
          r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(pk)"));
      return pending;
    } catch (ConditionalCheckFailedException e) {
      return find(pending.orderId())
          .orElseThrow(() -> new IllegalStateException("Concurrent aggregate missing", e));
    }
  }

  public boolean complete(Fulfillment completed, IntegrationEvent<CompletedFulfillment> event) {
    var update =
        Update.builder()
            .tableName(table)
            .key(key("FULFILLMENT#" + completed.orderId()))
            .updateExpression("SET body = :body, version = :next")
            .conditionExpression("version = :expected")
            .expressionAttributeValues(
                Map.of(
                    ":body",
                    s(Json.write(completed)),
                    ":next",
                    n(completed.version()),
                    ":expected",
                    n(completed.version() - 1)))
            .build();
    try {
      db.transactWriteItems(
          r ->
              r.transactItems(
                  TransactWriteItem.builder().update(update).build(),
                  TransactWriteItem.builder().put(DynamoOutboxStore.insert(table, event)).build()));
      return true;
    } catch (TransactionCanceledException e) {
      if (conditional(e)) return false;
      throw e;
    }
  }
}
