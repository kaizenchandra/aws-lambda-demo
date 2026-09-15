package com.synechisveltiosi.commerce.ordering.application.port.out;

import com.synechisveltiosi.commerce.contracts.AcceptedOrder;
import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.ordering.domain.Order;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
    Optional<Order> find(UUID id);

    /**
     * Atomically creates aggregate and pending integration event; returns false if aggregate exists.
     */
    boolean create(Order order, IntegrationEvent<AcceptedOrder> event);
}
