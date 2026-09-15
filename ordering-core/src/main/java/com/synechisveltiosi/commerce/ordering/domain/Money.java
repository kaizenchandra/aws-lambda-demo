package com.synechisveltiosi.commerce.ordering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount, String currency) {
  public Money {
    if (amount == null || amount.signum() < 0 || !"USD".equals(currency))
      throw new IllegalArgumentException("Invalid money");
    amount = amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  public Money times(int quantity) {
    return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
  }
}
