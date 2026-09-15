package com.synechisveltiosi.commerce.ordering.application.port.in;

import com.synechisveltiosi.commerce.ordering.domain.Order;

import java.util.List;
import java.util.UUID;

public interface OrderUseCases {
    Order create(String customerId, String idempotencyKey, List<Item> items);

    Order get(String customerId, UUID id);

    record Item(String sku, int quantity) {
    }
}
