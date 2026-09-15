package com.synechisveltiosi.commerce.fulfillment.application.port.in;

import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;
import java.util.UUID;

public interface FulfillmentUseCases {
  boolean process(IntegrationEvent<AcceptedOrder> event);

  Fulfillment get(String customer, UUID orderId);
}
