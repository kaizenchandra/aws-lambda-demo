package com.synechisveltiosi.commerce.fulfillment.application.port.out;

import com.synechisveltiosi.commerce.contracts.CompletedFulfillment;
import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;

import java.util.Optional;
import java.util.UUID;

public interface FulfillmentRepository {
    Optional<Fulfillment> find(UUID orderId);

    Fulfillment begin(Fulfillment pending);

    boolean complete(Fulfillment completed, IntegrationEvent<CompletedFulfillment> event);
}
