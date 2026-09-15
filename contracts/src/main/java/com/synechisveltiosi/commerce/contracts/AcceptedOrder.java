package com.synechisveltiosi.commerce.contracts;

import java.math.BigDecimal;
import java.util.List;

public record AcceptedOrder(
    String customerId, String currency, BigDecimal total, List<Item> items) {
  public AcceptedOrder {
    if (customerId == null
        || customerId.isBlank()
        || !"USD".equals(currency)
        || total == null
        || total.signum() <= 0
        || total.compareTo(new BigDecimal("100000.00")) > 0
        || total.scale() > 2) throw new IllegalArgumentException("Invalid order payload");
    total = total.setScale(2);
    items = List.copyOf(items);
    if (items.isEmpty()
        || items.size() > 20
        || items.stream().map(Item::sku).distinct().count() != items.size())
      throw new IllegalArgumentException("Invalid items");
    var calculated =
        items.stream()
            .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (calculated.compareTo(total) != 0) throw new IllegalArgumentException("Total mismatch");
  }

  public record Item(String sku, int quantity, BigDecimal unitPrice) {
    public Item {
      if (sku == null
          || !sku.matches("[A-Z0-9-]{1,40}")
          || quantity < 1
          || quantity > 100
          || unitPrice == null
          || unitPrice.signum() <= 0
          || unitPrice.scale() > 2) throw new IllegalArgumentException("Invalid item");
      unitPrice = unitPrice.setScale(2);
    }
  }
}
