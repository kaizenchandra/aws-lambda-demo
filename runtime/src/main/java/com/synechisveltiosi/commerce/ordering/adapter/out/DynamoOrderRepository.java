package com.synechisveltiosi.commerce.ordering.adapter.out;

import com.synechisveltiosi.commerce.contracts.AcceptedOrder;
import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.ordering.application.port.out.OrderRepository;
import com.synechisveltiosi.commerce.ordering.domain.Order;
import com.synechisveltiosi.commerce.platform.Json;
import com.synechisveltiosi.commerce.platform.outbox.DynamoOutboxStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static com.synechisveltiosi.commerce.platform.Dynamo.*;

public final class DynamoOrderRepository implements OrderRepository {
    private final DynamoDbClient db;
    private final String table;

    public DynamoOrderRepository(DynamoDbClient db, String table) {
        this.db = db;
        this.table = table;
    }

    public Optional<Order> find(UUID id) {
        var item =
                db.getItem(r -> r.tableName(table).key(key("ORDER#" + id)).consistentRead(true)).item();
        return item.isEmpty()
                ? Optional.empty()
                : Optional.of(Json.read(item.get("body").s(), Order.class));
    }

    public boolean create(Order order, IntegrationEvent<AcceptedOrder> event) {
        var item = new HashMap<String, AttributeValue>(key("ORDER#" + order.id()));
        item.put("body", s(Json.write(order)));
        try {
            db.transactWriteItems(
                    r ->
                            r.transactItems(
                                    TransactWriteItem.builder()
                                            .put(
                                                    Put.builder()
                                                            .tableName(table)
                                                            .item(item)
                                                            .conditionExpression("attribute_not_exists(pk)")
                                                            .build())
                                            .build(),
                                    TransactWriteItem.builder().put(DynamoOutboxStore.insert(table, event)).build()));
            return true;
        } catch (TransactionCanceledException e) {
            if (conditional(e)) return false;
            throw e;
        }
    }
}
