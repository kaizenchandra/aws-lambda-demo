package com.synechisveltiosi.commerce.ordering.domain;

import java.math.BigDecimal;
import java.util.Map;

/** Contract catalog; changes to prices require an explicit catalog release. */
public final class Catalog {
  private final Map<String, Money> prices =
      Map.of(
          "JAVA-GUIDE",
          new Money(new BigDecimal("29.90"), "USD"),
          "AWS-GUIDE",
          new Money(new BigDecimal("39.90"), "USD"));

  public OrderLine price(String sku, int quantity) {
    var price = prices.get(sku);
    if (price == null) throw new IllegalArgumentException("Unknown SKU");
    return new OrderLine(sku, quantity, price);
  }
}
