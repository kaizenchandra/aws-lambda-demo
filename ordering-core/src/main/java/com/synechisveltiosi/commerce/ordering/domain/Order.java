package com.synechisveltiosi.commerce.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Order(
        UUID id,
        String customerId,
        String requestFingerprint,
        List<OrderLine> lines,
        Instant acceptedAt) {
    public Order {
        Objects.requireNonNull(id);
        Objects.requireNonNull(acceptedAt);
        if (customerId == null
                || customerId.isBlank()
                || requestFingerprint == null
                || requestFingerprint.isBlank())
            throw new IllegalArgumentException("Missing order identity");
        lines = List.copyOf(lines);
        if (lines.isEmpty()
                || lines.size() > 20
                || lines.stream().map(OrderLine::sku).distinct().count() != lines.size())
            throw new IllegalArgumentException("Order requires 1–20 distinct items");
        if (sum(lines).compareTo(new BigDecimal("100000.00")) > 0)
            throw new IllegalArgumentException("Order limit exceeded");
    }

    private static BigDecimal sum(List<OrderLine> lines) {
        return lines.stream().map(l -> l.subtotal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Money total() {
        return new Money(sum(lines), "USD");
    }

    public OrderAccepted accepted() {
        return new OrderAccepted(id, acceptedAt);
    }

    public String status() {
        return "ACCEPTED";
    }
}
