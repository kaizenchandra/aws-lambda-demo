package com.synechisveltiosi.commerce.fulfillment.domain;

import java.util.UUID;

public record FulfillmentCompleted(UUID orderId, String receiptKey) {
}
