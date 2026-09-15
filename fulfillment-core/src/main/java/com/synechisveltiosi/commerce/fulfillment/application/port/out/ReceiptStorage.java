package com.synechisveltiosi.commerce.fulfillment.application.port.out;

import java.util.UUID;

public interface ReceiptStorage {
  String putImmutable(UUID orderId, String content);
}
