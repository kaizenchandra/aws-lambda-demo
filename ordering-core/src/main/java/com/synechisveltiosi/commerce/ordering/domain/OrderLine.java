package com.synechisveltiosi.commerce.ordering.domain;

public record OrderLine(String sku, int quantity, Money unitPrice) {
  public OrderLine {
    if (sku == null
        || !sku.matches("[A-Z0-9-]{1,40}")
        || quantity < 1
        || quantity > 100
        || unitPrice == null
        || unitPrice.amount().signum() <= 0)
      throw new IllegalArgumentException("Invalid order line");
  }

  public Money subtotal() {
    return unitPrice.times(quantity);
  }
}
